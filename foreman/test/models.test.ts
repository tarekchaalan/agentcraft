// Model display names on the agents' nameplates, and which models an engine accepts (/model).
import { describe, expect, it } from 'vitest';
import { effortsFor, isClaudeModel, modelLabel, modelProblem, normalizeModel } from '../src/agents/models.js';

describe('which models an engine runs', () => {
  it('Claude agents: the aliases, Claude model ids in any provider\'s spelling, Bedrock ARNs; a typo or a Codex model is caught', () => {
    for (const m of ['opus', 'Sonnet', 'HAIKU', 'fable', 'opus[1m]', 'opusplan', 'best', 'claude-opus-5-5', 'claude-sonnet-5', 'claude-haiku-4-5-20251001', 'us.anthropic.claude-opus-4-5-v1:0', 'claude-opus-4-5@20251101', 'arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/abc']) {
      expect(modelProblem('claude', m), m).toBeUndefined();
      expect(isClaudeModel(m), m).toBe(true);
    }
    for (const m of ['sonet', 'gpt-5', 'gpt-5.1-codex', 'o4-mini', 'llama3']) expect(modelProblem('claude', m), m).toBe('not-claude');
    for (const m of ['son net', 'opus;rm', '-opus', 'default', 'DEFAULT', '', 'x'.repeat(101)]) expect(modelProblem('claude', m), m).toBe('invalid');
  });

  it('Codex agents: any model name that is not a Claude one', () => {
    for (const m of ['gpt-5.1-codex', 'gpt-6-astra', 'o4-mini', 'codex-mini-latest', 'llama3']) expect(modelProblem('codex', m), m).toBeUndefined();
    for (const m of ['sonnet', 'Opus', 'claude-opus-5-5', 'us.anthropic.claude-haiku-4-5']) expect(modelProblem('codex', m), m).toBe('claude');
    expect(modelProblem('codex', 'gpt 5')).toBe('invalid');
  });

  it('keeps a Claude alias in lower case, anything else as typed; each engine has its own efforts', () => {
    expect(normalizeModel('claude', ' Sonnet ')).toBe('sonnet');
    expect(normalizeModel('claude', 'Opus[1m]')).toBe('opus[1m]');
    expect(normalizeModel('claude', 'claude-Sonnet-5')).toBe('claude-Sonnet-5');
    expect(normalizeModel('codex', 'GPT-5.1-codex')).toBe('GPT-5.1-codex');
    expect(effortsFor('claude')).toEqual(['low', 'medium', 'high', 'xhigh', 'max']);
    expect(effortsFor('codex')).toEqual(['none', 'minimal', 'low', 'medium', 'high', 'xhigh']);
  });
});

describe('modelLabel', () => {
  it.each([
    ['claude-opus-5-5', 'Opus 5.5'],
    ['claude-sonnet-5', 'Sonnet 5'],
    ['claude-haiku-4-5-20251001', 'Haiku 4.5'],
    ['claude-fable-5-1', 'Fable 5.1'],
    ['claude-opus-5-5[1m]', 'Opus 5.5'],
    ['opus', 'Opus'],
    ['sonnet', 'Sonnet'],
    ['gpt-6-astra', 'GPT-6 Astra'],
    ['gpt-5.1-codex-mini', 'GPT-5.1 Codex Mini'],
    ['o4-mini', 'o4 Mini'],
    ['my-local-model', 'my-local-model'],
  ])('%s -> %s', (model, label) => {
    expect(modelLabel(model)).toBe(label);
  });

  it('has no label for an unknown or default model', () => {
    expect(modelLabel(undefined)).toBeUndefined();
    expect(modelLabel('')).toBeUndefined();
    expect(modelLabel('default')).toBeUndefined();
  });
});
