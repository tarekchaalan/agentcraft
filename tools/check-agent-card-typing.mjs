#!/usr/bin/env node
// Keyboard-path check for the agent card's message line (agents feature). Needs a game started with
// AGENTCRAFT_DEV_TEST=1 (for dev.agents.keys): keys are queued as SDL key events for the game window and
// go through Minecraft's SDL event loop; printable keys also produce SDL text events only while SDL text
// input is on, as with a real keyboard (dev.type calls charTyped directly and would pass even when real
// typing is broken, which is what happened to the card's first message line).
//
//   node tools/check-agent-card-typing.mjs --port 7902 [--agent kit] [--prefix agents_typing/] [--send]
//
// Checks: with the card open and the line closed, typing produces no text and SDL text input is off;
// pressing M opens the line without a stray "m" and turns text input on; typed words arrive; a long
// message scrolls (the shot shows its tail); Backspace removes one character; Escape closes the line
// (text input off again); with --send, M + text + Enter sends it ("Sent to <name>").
import { DevClient } from './lib/devclient.mjs';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]] : acc), []),
);
const port = Number(args.port ?? process.env.AGENTCRAFT_DEV_PORT ?? 7879);
const agent = args.agent ?? 'kit';
const prefix = args.prefix ?? 'agents_typing/';
const dev = await DevClient.connect({ port });
const results = [];
const check = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== undefined ? `  (${JSON.stringify(detail)})` : ''}`);
};
const look = async () => {
  const r = await dev.call('dev.agents.look', { agent });
  return { input: r.card?.input ?? null, textInput: r.textInputActive, card: r.card };
};
const keys = (k) => dev.call('dev.agents.keys', { keys: k });

await dev.call('dev.agents.card', { agent });
await dev.call('dev.wait', { frames: 5 });
let s = await look();
check('card open, line closed, SDL text input off', s.card && s.input === null && s.textInput === false, s);
let r = await keys('x,y');
s = await look();
check('letters with the line closed type nothing (no text events)', s.input === null && r.textEvents === 0, { ...s, textEvents: r.textEvents });
r = await keys('m');
s = await look();
check('M opens the line with no stray "m", text input on', s.input === '' && s.textInput === true && r.textEvents === 0, { ...s, textEvents: r.textEvents });
await keys('please also cover emoji tags and a regression test for the parser');
s = await look();
check('typed words arrive through SDL', s.input === 'please also cover emoji tags and a regression test for the parser', s.input);
await dev.call('dev.screenshot', { name: `${prefix}long_message`, frames: 2, waitChunks: false });
await keys('back,back,back,back,back,back,back,back,back,back,back');
s = await look();
check('backspace removes characters', s.input === 'please also cover emoji tags and a regression test for', s.input);
await keys('escape');
s = await look();
check('Escape closes the line, text input off, card still open', s.card && s.input === null && s.textInput === false, s);
if (args.send) {
  await keys('m,hello from the keyboard');
  await dev.call('dev.screenshot', { name: `${prefix}typed`, frames: 2, waitChunks: false });
  await keys('return');
  await dev.call('dev.wait', { ms: 800 });
  s = await look();
  check('Enter sends and closes the line', s.input === null, s);
  await dev.call('dev.screenshot', { name: `${prefix}sent`, frames: 2, waitChunks: false });
}
await dev.call('dev.screen', { open: null });
const failed = results.filter((r) => !r.ok).length;
console.log(JSON.stringify({ passed: results.length - failed, failed }));
process.exit(failed ? 1 : 0);
