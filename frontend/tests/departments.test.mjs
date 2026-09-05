import test from 'node:test'
import assert from 'node:assert/strict'
import { loadDepartments } from '../src/departments.ts'

test('部门目录遍历全部分页，取消请求不发布迟到结果', async () => {
  const first = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, name: `部门${index}`, joined: false }))
  const paths = []
  const api = { json: async path => { paths.push(path); return paths.length === 1 ? first : [{ id: 101, name: '产品', joined: true }] } }
  const result = await loadDepartments(api, new AbortController().signal)
  assert.equal(result.length, 101)
  assert.equal(result.at(-1).joined, true)
  assert.deepEqual(paths, ['/departments?limit=100&afterId=0', '/departments?limit=100&afterId=100'])
  const controller = new AbortController()
  await assert.rejects(loadDepartments({ json: async () => { controller.abort(); return first } }, controller.signal), { name: 'AbortError' })
})
