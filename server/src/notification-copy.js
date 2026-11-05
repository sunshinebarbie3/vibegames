const COPY = {
  en: {
    INVITE: ['You have a group invitation', 'A member joined your group. Open VIBE to see what you can plan together.'],
    NEW_ACTIVITY: ['New idea on the Vibe List', 'A group member added a new idea to decide on.'],
    DEADLINE: ['Vote before the round closes', 'A YES or NO vote is still needed for each idea.'],
    WINNER: ['The group has a winner', 'Open VIBE to choose a date and time for the plan.'],
    MEMORY: ['A plan became a memory', 'Photos, a caption and the group rating are waiting.'],
  },
  zu: {
    INVITE: ['Unesimemo seqembu', 'Ilungu lijoyine iqembu lakho. Vula i-VIBE nihlele ndawonye.'],
    NEW_ACTIVITY: ['Umbono omusha ohlwini lwe-Vibe', 'Ilungu leqembu lengeze umbono omusha eningawuvotela.'],
    DEADLINE: ['Vota ngaphambi kokuvalwa', 'Kusadingeka ivoti lika-YEBO noma CHA ngombono ngamunye.'],
    WINNER: ['Iqembu selinophumelele', 'Vula i-VIBE ukhethe usuku nesikhathi sohlelo.'],
    MEMORY: ['Uhlelo seluyinkumbulo', 'Izithombe, umbhalo nesilinganiso seqembu kulindile.'],
  },
  st: {
    INVITE: ['O na le memo ya sehlopha', 'Setho se kene sehlopheng sa hao. Bula VIBE le rere mmoho.'],
    NEW_ACTIVITY: ['Mohopolo o motjha lenaneng la Vibe', 'Setho sa sehlopha se kentse mohopolo o motjha oo ka o voutelang.'],
    DEADLINE: ['Vouta pele potoloho e kwalwa', 'Ho sa hlokahala voutu ya E kapa TJHE mohopolong o mong le o mong.'],
    WINNER: ['Sehlopha se na le mohlodi', 'Bula VIBE o kgethe letsatsi le nako ya leano.'],
    MEMORY: ['Leano le fetohile mohopolo', 'Ditshwantsho, mongolo le tekanyo ya sehlopha di a o emetse.'],
  },
};
export function notificationCopy(language, type) {
  const lang = ['zu','st'].includes(language) ? language : 'en';
  const [title,body] = COPY[lang][type] || COPY[lang].NEW_ACTIVITY;
  return { title, body };
}
