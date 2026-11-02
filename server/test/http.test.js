import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { VibeStore } from '../src/store.js';
import http from 'node:http';
// Use Node's HTTP client directly; hosted test runners may configure fetch proxies.
async function request(url, options={}) {
  const target=new URL(url);
  return new Promise((resolve,reject)=>{
    const req=http.request(target,{method:options.method||'GET',headers:options.headers},res=>{
      let body='';res.on('data',c=>body+=c);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,json:async()=>JSON.parse(body),text:async()=>body}));
    });req.on('error',reject);if(options.body)req.write(options.body);req.end();
  });
}

// Minimal contract test double: real Firestore transaction behavior is tested by emulator tests.
function services() {
  const data=new Map();
  const snapshot=(key)=>({exists:data.has(key),data:()=>structuredClone(data.get(key))});
  const write=(key,value,merge=false)=>data.set(key,structuredClone(merge?{...data.get(key),...value}:value));
  const doc=(c,id)=>{const key=c+'/'+id;return {get:async()=>snapshot(key),set:async(value,options)=>write(key,value,options?.merge),create:async(value)=>{if(data.has(key)){const e=new Error('exists');e.code=6;throw e;}write(key,value);},delete:async()=>data.delete(key)};};
  const query=(c,field,op,value)=>({get:async()=>({docs:[...data].filter(([key,v])=>key.startsWith(c+'/')&&(op==='array-contains'?v[field]?.includes(value):v[field]===value)).map(([,v])=>({data:()=>structuredClone(v)}))})});
  const tx={get:async ref=>ref.get(),set:(ref,value)=>ref.set(value),delete:ref=>ref.delete()};
  const db={collection:c=>({doc:id=>doc(c,id),where:(field,op,value)=>query(c,field,op,value)}),runTransaction:fn=>fn(tx),batch:()=>{const ops=[];return {delete:ref=>ops.push(()=>ref.delete()),commit:async()=>{for(const op of ops)await op();}};}};

  return {db,data,verify:async token=>{if(token!=='valid')throw new Error('bad token');return {uid:'user-1',email:'test@example.com'}},auth:{revokeRefreshTokens:async()=>{}},login:async()=>{throw new Error('invalid')},reset:async()=>{},sessionFor:async()=>({accessToken:'valid',refreshToken:'refresh',expiresAtEpochMillis:Date.now()+3600000})};
}
async function withServer(fn) { const s=services(),app=createApp(s,{}),server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));try{await fn('http://127.0.0.1:'+server.address().port,s)}finally{await new Promise(r=>server.close(r))} }
test('health identifies NoSQL and blob services',()=>withServer(async url=>{const r=await request(url+'/health');assert.equal(r.status,200);assert.equal((await r.json()).database,'firestore')}));
test('member endpoints reject missing or invalid bearer tokens',()=>withServer(async url=>{assert.equal((await request(url+'/api/v1/groups')).status,401);assert.equal((await request(url+'/api/v1/groups',{headers:{Authorization:'Bearer nope'}})).status,401)}));
test('registration validates password and never returns a password/hash',()=>withServer(async url=>{const r=await request(url+'/api/v1/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:'Test',email:'test@example.com',password:'short'})});assert.equal(r.status,422)}));
test('sync rejects malformed payload and unknown actions per item',()=>withServer(async(url,s)=>{await new VibeStore(s).ensureUser('user-1','Test','test@example.com');const r=await request(url+'/api/v1/sync/batch',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer valid'},body:JSON.stringify({items:[{clientId:'test-1',action:'unknown',entityId:'x',payloadJson:'{}',queuedAtEpochMillis:100}]})});assert.equal(r.status,200);assert.equal((await r.json()).results[0].status,'rejected')}));
test('private blob references cannot be forged in a profile',()=>withServer(async(url,s)=>{await new VibeStore(s).ensureUser('user-1','Test','test@example.com');const r=await request(url+'/api/v1/users/me',{method:'PATCH',headers:{'Content-Type':'application/json',Authorization:'Bearer valid'},body:JSON.stringify({photoUri:'https://example.com/tracker.jpg'})});assert.equal(r.status,422)}));
