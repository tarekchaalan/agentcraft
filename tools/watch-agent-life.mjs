#!/usr/bin/env node
// Live observer for the agents feature (agent life): optionally submit a goal to the Foreman through
// the mod, then shoot one camera every N ms and log each agent's posture/family/bubble/particles plus
// fps, moving agents and plate overlaps (artifacts/logs/<prefix>live.jsonl).
//
//   node tools/watch-agent-life.mjs --port 7902 --seconds 60 --prefix agents_live/ [--goal "Add #tags"] \
//        [--camera cam_room | --camera x,y,z,lx,ly,lz] [--every 2500] [--foreman 32878 --answer-after 8]
// --answer-after S: answer every decision with its first option once it has been open S seconds
// (keeps the live sim moving; waiting states stay visible for a while first).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DevClient } from './lib/devclient.mjs';
import { ForemanClient } from './lib/foremanclient.mjs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const port = Number(args.port ?? process.env.AGENTCRAFT_DEV_PORT ?? 7879);
const seconds = Number(args.seconds ?? 60);
const prefix = args.prefix ?? 'agents_live/';
const cam = args.camera ?? 'cam_room';
const every = Number(args.every ?? 2500);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
fs.mkdirSync(path.join(root, 'artifacts', 'logs'), { recursive: true });
const log = fs.createWriteStream(path.join(root, 'artifacts', 'logs', `${prefix.replace(/[\\/]/g, '_')}live.jsonl`));

const dev = await DevClient.connect({ port });
const answerAfter = args['answer-after'] != null ? Number(args['answer-after']) * 1000 : null;
const fm = answerAfter != null ? await ForemanClient.connect({ port: Number(args.foreman ?? process.env.AGENTCRAFT_PORT ?? 7878) }) : null;
const seen = new Map();
if (args.goal) {
  const r = await dev.request('dev.foreman.send', { message: { type: 'goal.submit', text: args.goal } });
  console.log('goal', JSON.stringify(r.ack ?? r.error ?? r));
}
if (cam.startsWith('cam_')) {
  await dev.call('dev.camera', { anchor: cam });
} else if (cam !== 'keep') {
  const [x, y, z, lx, ly, lz] = cam.split(',').map(Number);
  await dev.call('dev.camera', { x, y, z, lookAt: { x: lx, y: ly, z: lz } });
}
const end = Date.now() + seconds * 1000;
for (let i = 0; Date.now() < end; i++) {
  const t0 = Date.now();
  const look = await dev.request('dev.agents.look');
  const st = await dev.request('dev.state');
  const shot = await dev.request('dev.screenshot', { name: `${prefix}${String(i).padStart(3, '0')}`, frames: 1, waitChunks: false });
  const agents = (look.agents || []).map(
    (a) => `${a.id}:${a.posture}${a.seated ? '(s)' : ''}/${a.family}${a.bubble ? ` "${a.bubble.slice(0, 28)}"` : ''}${a.particles ? ` p${a.particles}` : ''}`,
  );
  log.write(JSON.stringify({ i, t: t0, fps: st.fps, agents: st.agents, openDecisions: st.foreman?.counts?.openDecisions, look: look.agents }) + '\n');
  console.log(i, `fps=${st.fps} moving=${st.agents?.moving} overlaps=${st.agents?.plateOverlaps}${st.agents?.plateOverlapPairs ? `(${st.agents.plateOverlapPairs})` : ''} exclaims=${st.agents?.exclaims}/${st.foreman?.counts?.openDecisions} layoutUs=${st.agents?.plateLayoutUs}`, agents.join(' | '), shot.ok ? '' : shot.error);
  if (fm) {
    for (const d of fm.openDecisions()) {
      if (!seen.has(d.id)) seen.set(d.id, Date.now());
      if (Date.now() - seen.get(d.id) > answerAfter) {
        seen.set(d.id, Infinity);
        const opt = d.options?.[0];
        fm.send('decision.answer', { decisionId: d.id, ...(opt ? { option: opt } : { text: 'Sounds good' }) }).then(
          () => console.log(`answered ${d.id} (${d.kind}) with ${opt ?? 'text'}`),
          (e) => console.log(`answer ${d.id} failed: ${e.message}`),
        );
      }
    }
  }
  const wait = every - (Date.now() - t0);
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
}
log.end();
process.exit(0);
