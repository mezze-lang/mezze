/**
 * When the editor asks for completions at all.
 *
 * The engine answers a request at ANY position — on a blank line it returns
 * the whole keyword list — so the decision of when to ask belongs to the
 * editor. This mirrors that decision so it can be checked without a browser.
 *
 * Kept in step with askCompletions() in src/scripts/editor.ts by hand. If the
 * two drift, these tests pass while the editor misbehaves, so the copy below
 * is deliberately tiny: two conditions and nothing else.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

/** Would the editor ask for completions with the caret at `|`? */
function asks(marked) {
  const caret = marked.indexOf('|')
  const text = marked.slice(0, caret) + marked.slice(caret + 1)
  const upto = text.slice(0, caret)
  const curLine = upto.slice(upto.lastIndexOf('\n') + 1)
  const trigger = curLine.replace(/[A-Za-z0-9_]*$/, '').trimEnd().slice(-1)
  const afterColons = /::\w*$/.test(curLine)
  return trigger === '.' || afterColons || /^\s*(pub\s+)?use\s/.test(curLine)
}

describe('asks for completions', () => {
  test('inside a selective import block', () => {
    assert.equal(asks('use std::effects::async::{ |'), true)
  })

  test('after a dot', () => {
    assert.equal(asks('let x = n.|'), true)
  })

  test('while typing a method name', () => {
    assert.equal(asks('let x = n.to|'), true)
  })

  test('after a chained call', () => {
    assert.equal(asks('let x = xs.map { f = f }.|'), true)
  })

  test('on a use line', () => {
    assert.equal(asks('use |'), true)
    assert.equal(asks('use std::ef|'), true)
    assert.equal(asks('pub use std::|'), true)
  })

  test('after a qualified prefix', () => {
    assert.equal(asks('let x = map::|'), true)
    assert.equal(asks('let x = map::em|'), true)
  })

  test('indented, still on a use line', () => {
    assert.equal(asks('  use std::|'), true)
  })
})

describe('stays quiet', () => {
  test('on a blank line', () => {
    assert.equal(asks('let x = 1\n|'), false)
  })

  test('on a whitespace-only line', () => {
    assert.equal(asks('let main = {} -> do\n  |'), false)
  })

  test('mid-expression', () => {
    assert.equal(asks('let y = x + |'), false)
  })

  test('after a complete statement', () => {
    // The reported case: clicking to the end of an if/else and pausing.
    assert.equal(asks('  if cond then a else b|'), false)
  })

  test('while typing a bare identifier', () => {
    assert.equal(asks('let na|'), false)
  })

  test('inside a record, where arguments are out of scope', () => {
    // Deliberate: the engine cannot resolve a method's parameters while the
    // call is open, and returns unrelated items instead.
    assert.equal(asks('let a = rect { |'), false)
    assert.equal(asks('let m = x.insert { |'), false)
  })


})
