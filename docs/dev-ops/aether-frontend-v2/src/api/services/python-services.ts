/** API client for Aether Python microservices.
 *
 * All requests go through Vite proxy (same-origin), so no CORS issues.
 * When services are down, the proxy returns 502 immediately instead of hanging.
 */
import axios, { type AxiosInstance } from 'axios'

// ── Service clients (use Vite proxy: /doc → :8001, /sandbox → :8002, /fs → :8003) ──

const docClient: AxiosInstance = axios.create({ baseURL: '/doc', timeout: 60_000 })
const sandboxClient: AxiosInstance = axios.create({ baseURL: '/sandbox', timeout: 300_000 })
// fs 服务：本地文件操作应秒级完成；服务挂起时 15s 内快速失败，避免"一直转圈"
const fsClient: AxiosInstance = axios.create({ baseURL: '/fs', timeout: 15_000 })

// ── Shared error interceptor for better error messages ─────────

function enhanceError(error: unknown): never {
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED') {
      throw new Error('服务响应超时（可能已挂起）— 请重启 Docker Python 服务后重试')
    }
    if (!error.response) {
      throw new Error('无法连接服务 — 请确认 Docker 服务已启动 (docker compose up -d)')
    }
    if (error.response.status === 502 || error.response.status === 503) {
      throw new Error('Docker 服务不可用 — 请确认 docker compose up -d 已执行')
    }
    if (error.response.status >= 500) {
      throw new Error(`服务错误 (${error.response.status})`)
    }
    throw new Error(error.message || '网络请求失败')
  }
  throw error
}

// Apply interceptor to all clients
;[fsClient, docClient, sandboxClient].forEach(client => {
  client.interceptors.response.use(r => r, error => enhanceError(error))
})

// ── Types ──────────────────────────────────────────────────

export interface FileEntry {
  name: string
  type: 'file' | 'dir'
  size_bytes: number
  mtime_iso: string | null
}

export interface DirListResponse {
  path: string
  entries: FileEntry[]
  count: number
}

export interface ReadResponse {
  path: string
  lines: string
  total_lines: number
  offset: number
  limit: number
  truncated: boolean
  next_offset: number | null
}

export interface WriteResponse {
  path: string
  size_bytes: number
  checksum: string
}

export interface EditResponse {
  path: string
  size_bytes: number
  replacements: number
}

export interface ExistsResponse {
  path: string
  exists: boolean
  size_bytes: number | null
  mtime_iso: string | null
  is_dir: boolean
}

export interface ExecuteResponse {
  exit_code: number
  stdout: string
  stderr: string
  execution_time_ms: number
  truncated: boolean
}

export interface LanguageInfo {
  name: string
  version: string
  extensions: string[]
}

export interface SandboxHealth {
  status: string
  workspace: string
  languages: LanguageInfo[]
  container_running: boolean
}

export interface DocOperationResult {
  path: string
  format: string
  size_bytes: number
}

export interface SearchResponse {
  pattern: string
  path: string
  mode: string
  matches: string[]
  count: number
}

// ── Bridge types ─────────────────────────────────────────

export interface BridgeValidateRequest {
  path: string
}

export interface BridgeValidateResponse {
  valid: boolean
  resolved: string | null
  display_name: string | null
  tree: FileEntry[]
  error: string | null
}

export interface BridgeBrowseRequest {
  path: string
  subpath: string
}

export interface BridgeBrowseResponse {
  path: string
  entries: FileEntry[]
  count: number
}

export interface BridgeMountRequest {
  path: string
  name: string
  session_id: string
}

export interface BridgeMountResponse {
  name: string
  workspace_path: string
  host_path: string
}

export interface BridgeUnmountRequest {
  name: string
}

export interface BridgeUnmountResponse {
  ok: boolean
}

export interface MountInfo {
  name: string
  host_path: string
  workspace_path: string
  session_id: string
}

export interface BridgeListRequest {
  session_id?: string | null
}

export interface BridgeListResponse {
  mounts: MountInfo[]
}

// ── File System API ────────────────────────────────────────

export const fsApi = {
  health: () => fsClient.get('/health').then(r => r.data),

  listDir: (path: string, recursive = false) =>
    fsClient.post<DirListResponse>('/dir/list', { path, recursive }).then(r => r.data),

  createDir: (path: string) =>
    fsClient.post('/dir/create', { path, exist_ok: true }).then(r => r.data),

  deleteDir: (path: string, recursive = false) =>
    fsClient.post('/dir/delete', { path, recursive }).then(r => r.data),

  readFile: (path: string, offset = 1, limit = 500) =>
    fsClient.post<ReadResponse>('/file/read', { path, offset, limit }).then(r => r.data),

  writeFile: (path: string, content: string) =>
    fsClient.post<WriteResponse>('/file/write', { path, content }).then(r => r.data),

  editFile: (path: string, oldStr: string, newStr: string, replaceAll = false) =>
    fsClient.post<EditResponse>('/file/edit', { path, old_string: oldStr, new_string: newStr, replace_all: replaceAll }).then(r => r.data),

  deleteFile: (path: string) =>
    fsClient.post('/file/delete', { path }).then(r => r.data),

  moveFile: (from: string, to: string) =>
    fsClient.post('/file/move', { from_path: from, to_path: to }).then(r => r.data),

  exists: (path: string) =>
    fsClient.post<ExistsResponse>('/file/exists', { path }).then(r => r.data),

  search: (pattern: string, path = '.', glob?: string, mode: 'content' | 'files_only' | 'count' = 'content') =>
    fsClient.post<SearchResponse>('/search', { pattern, path, glob, mode }).then(r => r.data),

  upload: (file: File, path = '/workspace/uploads') => {
    const formData = new FormData()
    formData.append('file', file)
    return fsClient.post<{ path: string; size_bytes: number; checksum: string; filename: string }>(
      `/file/upload?path=${encodeURIComponent(path)}`,
      formData
    ).then(r => r.data)
  },

  browse: (path: string) =>
    fsClient.post<{ path: string; entries: FileEntry[]; count: number }>('/browse', { path }).then(r => r.data),
}

// ── Sandbox API ────────────────────────────────────────────

export const sandboxApi = {
  health: () => sandboxClient.get<SandboxHealth>('/health').then(r => r.data),

  execute: (language: string, code: string, timeout = 60) =>
    sandboxClient.post<ExecuteResponse>('/execute', { language, code, timeout }).then(r => r.data),

  languages: () => sandboxClient.get<LanguageInfo[]>('/languages').then(r => r.data),
}

// ── Document API ────────────────────────────────────────────

export const docApi = {
  health: () => docClient.get('/health').then(r => r.data),

  read: (path: string, format: 'docx' | 'pptx' | 'xlsx') =>
    docClient.post('/read', { path, format }).then(r => r.data),

  create: (path: string, format: 'docx' | 'pptx' | 'xlsx', content: unknown) =>
    docClient.post<DocOperationResult>('/create', { path, format, content }).then(r => r.data),

  modify: (path: string, format: 'docx' | 'pptx' | 'xlsx', operations: unknown[]) =>
    docClient.post<DocOperationResult>('/modify', { path, format, operations }).then(r => r.data),
}

// ── Bridge API ─────────────────────────────────────────

export const bridgeApi = {
  validate: (path: string) =>
    fsClient.post<BridgeValidateResponse>('/bridge/validate', { path }).then(r => r.data),

  browse: (path: string, subpath = '.') =>
    fsClient.post<BridgeBrowseResponse>('/bridge/browse', { path, subpath }).then(r => r.data),

  mount: (path: string, name: string, sessionId: string) =>
    fsClient.post<BridgeMountResponse>('/bridge/mount', { path, name, session_id: sessionId }).then(r => r.data),

  unmount: (name: string) =>
    fsClient.post<BridgeUnmountResponse>('/bridge/unmount', { name }).then(r => r.data),

  list: (sessionId?: string | null) =>
    fsClient.post<BridgeListResponse>('/bridge/list', { session_id: sessionId ?? null }).then(r => r.data),
}

// ── Pipeline helpers ───────────────────────────────────────

export interface PipelineStep {
  id: number
  name: string
  status: 'pending' | 'running' | 'ok' | 'fail'
  detail: string
  time?: number
}

export async function runPipeline(
  workspace: string,
  onStep: (step: PipelineStep) => void
): Promise<boolean> {
  const steps: PipelineStep[] = [
    { id: 1, name: '创建销售数据文件', status: 'pending', detail: '' },
    { id: 2, name: '生成 Excel 表格', status: 'pending', detail: '' },
    { id: 3, name: 'Python 数据分析', status: 'pending', detail: '' },
    { id: 4, name: 'JavaScript 生成摘要', status: 'pending', detail: '' },
    { id: 5, name: 'Bash 元数据生成', status: 'pending', detail: '' },
    { id: 6, name: 'Java 统计计算', status: 'pending', detail: '' },
    { id: 7, name: '生成 PPT 报告', status: 'pending', detail: '' },
    { id: 8, name: '生成 Word 报告', status: 'pending', detail: '' },
    { id: 9, name: '验证生成文件', status: 'pending', detail: '' },
  ]

  const emit = (idx: number, status: PipelineStep['status'], detail: string) => {
    steps[idx].status = status
    steps[idx].detail = detail
    onStep({ ...steps[idx] })
  }

  let ok = true
  try {
    // Step 1: Create data file
    emit(0, 'running', '写入 sales_data.json...')
    const data = {
      sales: [
        { region: '华东', q1: 45000, q2: 52000, q3: 61000, q4: 78000 },
        { region: '华南', q1: 38000, q2: 41000, q3: 44000, q4: 52000 },
        { region: '华北', q1: 62000, q2: 58000, q3: 71000, q4: 85000 },
        { region: '西南', q1: 29000, q2: 33000, q3: 36000, q4: 41000 },
        { region: '华中', q1: 51000, q2: 54000, q3: 59000, q4: 67000 },
      ],
    }
    await fsApi.writeFile(`${workspace}/sales_data.json`, JSON.stringify(data, null, 2))
    emit(0, 'ok', '5 个区域数据已写入')

    // Step 2: Excel
    emit(1, 'running', '创建 report_data.xlsx...')
    const rows: (string | number)[][] = [['区域', 'Q1', 'Q2', 'Q3', 'Q4']]
    data.sales.forEach(s => rows.push([s.region, s.q1, s.q2, s.q3, s.q4]))
    await docApi.create(`${workspace}/report_data.xlsx`, 'xlsx', {
      sheets: { '销售数据': { rows } },
    })
    emit(1, 'ok', 'Excel 表格已生成')

    // Step 3: Python analysis
    emit(2, 'running', '执行 Python 分析...')
    const pyRes = await sandboxApi.execute('python', `
import json
from pathlib import Path
data = json.loads(Path('${workspace}/sales_data.json').read_text())
regions = [{"region": s["region"], "total": s["q1"]+s["q2"]+s["q3"]+s["q4"],
            "avg": (s["q1"]+s["q2"]+s["q3"]+s["q4"])/4,
            "max": max(s["q1"],s["q2"],s["q3"],s["q4"])} for s in data["sales"]]
regions.sort(key=lambda x: x["total"], reverse=True)
gt = sum(r["total"] for r in regions)
results = {"regions": regions, "grand_total": gt, "avg_total": round(gt/len(regions),2),
           "best": regions[0], "date": "2026-08-04", "count": len(regions)}
Path('${workspace}/results.json').write_text(json.dumps(results, indent=2, ensure_ascii=False))
print(f"分析完成: {results['count']} 个区域, 总计 ${'{results[\"grand_total\"]:,}'}")
    `, 30)
    if (pyRes.exit_code !== 0) throw new Error(pyRes.stdout)
    emit(2, 'ok', pyRes.stdout.trim())

    // Step 4: JS summary
    emit(3, 'running', 'Node.js 格式化...')
    const jsRes = await sandboxApi.execute('javascript', `
const fs = require("fs");
const r = JSON.parse(fs.readFileSync("${workspace}/results.json","utf8"));
let lines = ["=== SALES SUMMARY ===", "Total: $"+r.grand_total.toLocaleString(), "Best: "+r.best.region];
r.regions.forEach((x,i) => lines.push((i+1)+". "+x.region+": $"+x.total.toLocaleString()));
fs.writeFileSync("${workspace}/summary.txt", lines.join("\\n"));
console.log(lines.slice(0,3).join("\\n"));
    `, 15)
    if (jsRes.exit_code !== 0) emit(3, 'fail', jsRes.stdout)
    else emit(3, 'ok', jsRes.stdout.trim())

    // Step 5: Bash metadata
    emit(4, 'running', '生成元数据...')
    const bashRes = await sandboxApi.execute('bash', `
cat > ${workspace}/metadata.txt << 'EOF'
# Aether Pipeline Metadata
generated_at: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
workspace: ${workspace}
files: $(ls ${workspace}/ | wc -l)
EOF
echo "元数据已生成"
    `, 10)
    if (bashRes.exit_code !== 0) emit(4, 'fail', bashRes.stdout)
    else emit(4, 'ok', bashRes.stdout.trim())

    // Step 6: Java stats
    emit(5, 'running', 'Java 计算...')
    const javaRes = await sandboxApi.execute('java', `
public class Main {
  public static void main(String[] a) {
    double[] v = {45000,52000,61000,78000,38000,41000,44000,52000,62000,58000,71000,85000,29000,33000,36000,41000,51000,54000,59000,67000};
    double s = 0, min = v[0], max = v[0];
    for (double x : v) { s += x; if (x < min) min = x; if (x > max) max = x; }
    System.out.printf("Count: %d, Sum: $%,.0f, Avg: $%,.0f%n", v.length, s, s/v.length);
  }
}
    `.trim(), 20)
    if (javaRes.exit_code !== 0) emit(5, 'fail', javaRes.stdout)
    else emit(5, 'ok', javaRes.stdout.trim())

    // Step 7: PPT
    emit(6, 'running', '生成 PPT...')
    const pptRes = await docApi.create(`${workspace}/report.pptx`, 'pptx', {
      slides: [
        { title: '销售分析报告', subtitle: '2026 年度区域业绩', layout: '0', shapes: [] },
        { title: '核心指标', shapes: [
          { type: 'text', text: `总销售额: $${data.sales.reduce((a,b)=>a+b.q1+b.q2+b.q3+b.q4,0).toLocaleString()}`, left: 1, top: 2, width: 6, height: 0.8, font_size: 20, bold: true },
          { type: 'text', text: `区域数: ${data.sales.length}`, left: 1, top: 3.2, width: 6, height: 0.8, font_size: 16 },
        ]},
      ],
    })
    emit(6, 'ok', `PPT 已创建 (${pptRes.size_bytes} bytes)`)

    // Step 8: Word
    emit(7, 'running', '生成 Word...')
    const docRes = await docApi.create(`${workspace}/report.docx`, 'docx', {
      paragraphs: [
        { text: '销售分析报告', style: 'Title', alignment: 'CENTER', bold: true, font_size: 24 },
        { text: '2026 年度区域业绩分析', style: 'Subtitle', alignment: 'CENTER', font_size: 14 },
        { text: '', style: 'Normal' },
        { text: '核心数据', style: 'Heading 1', bold: true, font_size: 18 },
        ...data.sales.map((s, i) => ({ text: `${i+1}. ${s.region}: Q1=$${s.q1} Q2=$${s.q2} Q3=$${s.q3} Q4=$${s.q4}`, style: 'Normal', font_size: 12 })),
        { text: '', style: 'Normal' },
        { text: '由 Aether Agent 自动生成 | 2026-08-04', style: 'Normal', alignment: 'CENTER', italic: true, font_size: 10 },
      ],
      tables: [],
    })
    emit(7, 'ok', `Word 已创建 (${docRes.size_bytes} bytes)`)

    // Step 9: Verify
    emit(8, 'running', '验证文件...')
    const dir = await fsApi.listDir(workspace)
    const reportFiles = dir.entries.filter(e => ['report.pptx', 'report.docx', 'report_data.xlsx'].includes(e.name))
    emit(8, 'ok', `共 ${dir.count} 个文件, 报告: ${reportFiles.map(e => e.name).join(', ')}`)

  } catch (e: unknown) {
    ok = false
  }
  return ok
}
