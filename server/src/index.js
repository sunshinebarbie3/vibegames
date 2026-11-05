import { firebaseServices } from './firebase.js';
import { createApp } from './app.js';
const port = Number(process.env.PORT || 8080);
const app = createApp(firebaseServices());
const server = app.listen(port, '0.0.0.0', () => console.log(`VIBE Firebase REST API listening on ${port}`));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)));
