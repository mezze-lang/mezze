import * as cp from 'child_process';
import * as net from 'net';
import * as path from 'path';
import * as fs from 'fs';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;

/** Resolve the `mez` executable path the same way the LSP client does. */
function resolveMezPath(): string {
    const config = vscode.workspace.getConfiguration('mezze');
    let mezPath = config.get<string>('executablePath') || 'mez';
    if (!path.isAbsolute(mezPath) && mezPath !== 'mez') {
        const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
        if (workspaceFolder) {
            mezPath = path.join(workspaceFolder.uri.fsPath, mezPath);
        }
    }
    return mezPath;
}

// --- `mez sample` as a table ------------------------------------------------
// The "N branch inputs · sample" CodeLens fires `mezze.sample`; instead of a raw
// terminal dump we run `mez sample --json` and render the input→output rows in a
// webview table (with a resample button). The JSON is produced by the CLI's
// SampleCommand.sampleJson.
let samplePanel: vscode.WebviewPanel | undefined;
let sampleTarget: { root: string; file: string; fn: string; seed: number; count: number } | undefined;
// The function whose table is currently rendered — so a re-sample of the SAME
// function keeps its rows on screen instead of flashing a loading placeholder.
let sampleShownFn: string | undefined;
const SAMPLE_INITIAL_COUNT = 6, SAMPLE_STEP = 6, SAMPLE_MAX_COUNT = 60;

function runSampleJson(root: string, file: string, fn: string, seed: number, count: number): Promise<{ data: any | null; err: string }> {
    // row i is always seed+i, so the same seed reproduces rows (retain) and a
    // larger count only appends more — "more rows" keeps what's already shown.
    // `--file` points the CLI at the module the lens came from, so a function in
    // any open file is sampleable, not only the project's app module.
    const args = ['sample', '--json', `--file=${file}`, `--seed=${seed}`, `--count=${count}`, fn];
    return new Promise((resolve) => {
        cp.execFile(resolveMezPath(), args, { cwd: root, maxBuffer: 16 * 1024 * 1024 }, (error, stdout, stderr) => {
            let data: any = null;
            try { data = JSON.parse(stdout); } catch { /* not json — surface stderr below */ }
            resolve({ data, err: data ? '' : (stderr || stdout || String(error || 'mez sample failed')) });
        });
    });
}

function escHtml(s: string): string {
    return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string));
}

// Readable text for a sampled value node (scalar/record/list/variant).
function valueText(node: any): string {
    if (node == null) { return ''; }
    if (node.node === 'scalar' && typeof node.text === 'string') { return node.text; }
    switch (node.node) {
        case 'record':
            return '{ ' + (node.fields || []).map((f: any) => `${f.name} = ${valueText(f.value)}`).join(', ') + ' }';
        case 'list':
            return '[' + (node.items || node.elements || []).map(valueText).join(', ') + ']';
        case 'variant':
            return String(node.name || '') +
                (node.fields && node.fields.length ? ` { ${node.fields.map((f: any) => `${f.name} = ${valueText(f.value)}`).join(', ')} }` : '');
        default:
            return typeof node.text === 'string' ? node.text : JSON.stringify(node);
    }
}

function sampleTableHtml(d: any, fn: string): string {
    const rows: any[] = Array.isArray(d.rows) ? d.rows : [];
    // Per-field input columns when every row's input is a record with fields.
    const firstRec = rows.find((r) => r.args?.value?.node === 'record')?.args?.value;
    const fieldNames: string[] = firstRec ? (firstRec.fields || []).map((f: any) => f.name) : [];
    const perField = fieldNames.length > 0 && rows.every((r) => !r.args || r.args.value?.node === 'record');
    const nIn = perField ? fieldNames.length : 1;
    const hasBranch = rows.some((r) => (r.why ?? '') !== '');

    // Two-row header: a group band (INPUT over the argument columns, OUTPUT,
    // BRANCH) above the actual column names — so "input" and "output" are always
    // labelled even when the inputs are broken out per field.
    const groupRow =
        `<tr class="grp">` +
        `<th class="in" colspan="${nIn}">input</th>` +
        `<th class="sep"></th>` +
        `<th class="out">output</th>` +
        (hasBranch ? `<th class="br">branch</th>` : '') +
        `</tr>`;
    const colRow =
        `<tr class="cols">` +
        (perField ? fieldNames.map((n) => `<th class="in">${escHtml(n)}</th>`).join('') : `<th class="in">args</th>`) +
        `<th class="sep"></th><th class="out">value</th>` +
        (hasBranch ? `<th class="br">why chosen</th>` : '') +
        `</tr>`;

    const body = rows.map((r) => {
        const crashed = typeof r.crashed === 'string';
        const out = crashed ? r.crashed : (r.result?.text ?? '');
        const why = r.why ?? '';
        let cells: string;
        if (perField) {
            const byName: Record<string, any> = {};
            for (const f of (r.args?.value?.fields || [])) { byName[f.name] = f.value; }
            cells = fieldNames.map((n) => `<td class="in"><code>${escHtml(valueText(byName[n]))}</code></td>`).join('');
        } else {
            cells = `<td class="in"><code>${escHtml(r.args?.text ?? '')}</code></td>`;
        }
        return `<tr class="${crashed ? 'crashed' : ''}">${cells}<td class="sep">→</td>` +
               `<td class="out"><code>${escHtml(out)}</code>${crashed ? ' <span class="tag">crash</span>' : ''}</td>` +
               (hasBranch ? `<td class="br">${escHtml(why)}</td>` : '') + `</tr>`;
    }).join('');

    const span = nIn + 2 + (hasBranch ? 1 : 0);
    const unreached: any[] = Array.isArray(d.unreached) ? d.unreached : [];
    const cov = unreached.length
        ? `<p class="note warn">${unreached.length} branch(es) not covered: ${unreached.map((u) => escHtml(`${u.branch} (${u.file}:${u.line})`)).join(', ')}</p>`
        : (rows.length && hasBranch ? `<p class="note ok">all branches covered</p>` : '');
    const newtypes: string[] = Array.isArray(d.unchecked_newtypes) ? d.unchecked_newtypes : [];
    const nt = newtypes.length ? `<p class="note">newtypes drawn from their representation: ${newtypes.map(escHtml).join(', ')}</p>` : '';

    return `<!doctype html><html><head><meta charset="utf-8"><style>
      :root { --line: var(--vscode-panel-border, #8883); --accent: var(--vscode-textLink-foreground, #4ea1ff); }
      body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 12px 16px; }
      h2 { font-size: 1.05em; font-weight: 600; margin: 0 0 1px; }
      h2 code { font-family: var(--vscode-editor-font-family, monospace); }
      .meta { opacity: .75; font-size: .82em; margin: 2px 0 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
      .meta .count { font-variant-numeric: tabular-nums; }
      .spacer { flex: 1; }
      button { font: inherit; font-size: .85em; color: var(--vscode-button-secondaryForeground, var(--vscode-foreground));
               background: var(--vscode-button-secondaryBackground, transparent); border: 1px solid var(--line);
               padding: 2px 10px; border-radius: 4px; cursor: pointer; }
      button:hover { background: var(--vscode-button-hoverBackground); border-color: var(--accent); }
      .card { border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
      table { border-collapse: collapse; width: 100%; }
      th, td { text-align: left; padding: 5px 12px; vertical-align: top; }
      /* group band */
      thead tr.grp th { font-size: .68em; font-weight: 700; letter-spacing: .08em; text-transform: uppercase;
                        opacity: .6; padding-bottom: 1px; padding-top: 7px; }
      thead tr.grp th.in  { color: var(--accent); }
      /* column names */
      thead tr.cols th { font-size: .82em; font-weight: 600; opacity: .8; border-bottom: 1px solid var(--line); padding-top: 0; padding-bottom: 6px; }
      tbody td { border-bottom: 1px solid color-mix(in srgb, var(--line) 55%, transparent); }
      tbody tr:last-child td { border-bottom: 0; }
      tbody tr:nth-child(even) { background: color-mix(in srgb, var(--vscode-foreground) 4%, transparent); }
      tbody tr:hover { background: color-mix(in srgb, var(--accent) 12%, transparent); }
      code { font-family: var(--vscode-editor-font-family, monospace); white-space: pre-wrap; }
      td.in code { font-variant-numeric: tabular-nums; }
      td.out { font-weight: 600; }
      .in + .sep, td.in:last-of-type { } /* no-op anchor */
      .sep { color: var(--accent); opacity: .55; text-align: center; width: 1.6em; font-weight: 700; }
      th.out, td.out { border-left: 1px solid var(--line); }
      .br { opacity: .65; font-size: .82em; }
      tr.crashed td.out { color: var(--vscode-errorForeground); }
      .tag { font-size: .7em; vertical-align: middle; background: var(--vscode-errorForeground); color: var(--vscode-editor-background);
             padding: 1px 6px; border-radius: 4px; margin-left: 4px; }
      .note { font-size: .82em; opacity: .85; margin: 12px 0 0; }
      .note.ok { color: var(--vscode-testing-iconPassed, #3a3); }
      .note.warn { color: var(--vscode-testing-iconQueued, #c80); }
      .repro { opacity: .55; }
    </style></head><body>
      <h2>sample · <code>${escHtml(fn)}</code></h2>
      <div class="meta">
        <span class="count">${rows.length} row(s)</span><span>·</span><span>seed ${escHtml(String(d.seed ?? ''))}</span>
        <span class="spacer"></span>
        <button onclick="post('more')" title="draw more inputs, keeping these">+ more</button>
        <button onclick="post('resample')" title="new seed — a fresh set of inputs">↻ resample</button>
      </div>
      <div class="card"><table>
        <thead>${groupRow}${colRow}</thead>
        <tbody>${body || `<tr><td colspan="${span}" style="opacity:.6;padding:14px">no sampleable rows</td></tr>`}</tbody>
      </table></div>
      ${cov}${nt}
      <p class="note repro">deterministic — <code>mez sample --seed=${escHtml(String(d.seed ?? ''))} ${escHtml(fn)}</code> reproduces these exact rows.</p>
      <script>const vs = acquireVsCodeApi(); function post(cmd){ vs.postMessage({ cmd }); }</script>
    </body></html>`;
}

function sampleErrHtml(fn: string, msg: string): string {
    return `<!doctype html><html><body style="font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 12px;">
      <h2 style="font-size:1.1em;">sample · ${escHtml(fn)}</h2>
      <p style="color: var(--vscode-errorForeground);">Could not sample this function.</p>
      <pre style="white-space: pre-wrap; opacity:.85;">${escHtml(msg)}</pre></body></html>`;
}

async function showSample(root: string, file: string, fn: string, seed: number, count: number): Promise<void> {
    sampleTarget = { root, file, fn, seed, count };
    if (!samplePanel) {
        samplePanel = vscode.window.createWebviewPanel(
            'mezzeSample', `sample · ${fn}`,
            { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
            { enableScripts: true },
        );
        samplePanel.onDidDispose(() => { samplePanel = undefined; sampleShownFn = undefined; });
        samplePanel.webview.onDidReceiveMessage((m) => {
            if (!sampleTarget) { return; }
            if (m?.cmd === 'resample') {
                // new seed → a fresh set of inputs, back to the initial count
                void showSample(sampleTarget.root, sampleTarget.file, sampleTarget.fn, Math.floor(Math.random() * 1e9), SAMPLE_INITIAL_COUNT);
            } else if (m?.cmd === 'more') {
                // same seed, larger count → keeps the rows shown and appends more
                void showSample(sampleTarget.root, sampleTarget.file, sampleTarget.fn, sampleTarget.seed, Math.min(sampleTarget.count + SAMPLE_STEP, SAMPLE_MAX_COUNT));
            }
        });
    }
    samplePanel.title = `sample · ${fn}`;
    // Only show the "sampling…" placeholder when there is nothing useful to keep
    // on screen — a first open, or a different function. For "+ more rows" and
    // "resample" (same function) we leave the current table visible while the new
    // data loads, so the rows never blink away before the fuller set arrives.
    if (sampleShownFn !== fn) {
        samplePanel.webview.html = `<!doctype html><body style="font-family: var(--vscode-font-family); padding: 14px; opacity:.7;">sampling <code>${escHtml(fn)}</code>…</body>`;
    }
    samplePanel.reveal(vscode.ViewColumn.Beside, true);
    const { data, err } = await runSampleJson(root, file, fn, seed, count);
    if (!samplePanel) { return; }
    sampleShownFn = fn;
    samplePanel.webview.html = data ? sampleTableHtml(data, fn) : sampleErrHtml(fn, err);
}

export function activate(context: vscode.ExtensionContext) {
    // Get the path to the mez executable
    const mezPath = resolveMezPath();

    // Wire the debugger (DAP) — F5 on a .mz file launches `mez exec --dap`.
    registerDebugAdapter(context, mezPath);

    // Server options - spawn `mez lsp` process
    const serverOptions: ServerOptions = {
        command: mezPath,
        args: ['lsp'],
        transport: TransportKind.stdio
    };

    // Client options
    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'mezze' }],
        synchronize: {
            // Watch Mezze.toml for project configuration changes
            fileEvents: vscode.workspace.createFileSystemWatcher('**/Mezze.toml'),
            // Push the `mezze.*` settings section to the server on start and on
            // change (the server reads debounce / inlay / codeLens toggles from
            // didChangeConfiguration).
            configurationSection: 'mezze'
        },
        outputChannelName: 'Mezze Language Server'
    };

    // Create and start the language client
    client = new LanguageClient(
        'mezze',
        'Mezze Language Server',
        serverOptions,
        clientOptions
    );

    // Register commands
    context.subscriptions.push(
        vscode.commands.registerCommand('mezze.restartServer', async () => {
            if (client) {
                await client.stop();
                await client.start();
                vscode.window.showInformationMessage('Mezze Language Server restarted');
            }
        }),
        // Clear the server's content-addressed analysis cache. Useful after a
        // stale-cache glitch (e.g. a mez version bump) without a full restart.
        //
        // IMPORTANT: the palette command ID must NOT collide with the server's
        // own executeCommand IDs (`mezze.clearCache` / `mezze.restart`).
        // vscode-languageclient auto-registers a VS Code command for every ID
        // the server advertises in executeCommandProvider; registering the same
        // ID here throws `command '…' already exists` during client.start(),
        // which fails LSP startup entirely (symptom: "Server process exited with
        // code 0"). So the palette commands use distinct `…Command` IDs and
        // forward to the server command by name.
        vscode.commands.registerCommand('mezze.clearCacheCommand', async () => {
            if (!client) { return; }
            await client.sendRequest('workspace/executeCommand', {
                command: 'mezze.clearCache', arguments: []
            });
            vscode.window.showInformationMessage('Mezze: analysis cache cleared');
        }),
        // Full server-side reset (reload store + re-analyze open docs) without
        // tearing down the client connection.
        vscode.commands.registerCommand('mezze.resetServerState', async () => {
            if (!client) { return; }
            await client.sendRequest('workspace/executeCommand', {
                command: 'mezze.restart', arguments: []
            });
            vscode.window.showInformationMessage('Mezze: server state reset');
        })
    );

    // Note: the client's `synchronize.configurationSection: 'mezze'` (below)
    // already pushes the `mezze.*` settings to the server on start and on every
    // change, so no manual onDidChangeConfiguration handler is needed here.

    // CodeLens client commands. The server emits CodeLenses whose command is
    // one of these with args [uriString, Position]; each opens VS Code's peek
    // view over the relevant locations. Without these handlers, clicking a
    // "N references" / "N implementations" / "N performers" lens fails with
    // "command 'mezze.showReferences' not found".
    //
    // The server passes only (uri, position) — not the locations — so we run
    // the corresponding provider here to gather them, then invoke the built-in
    // peek command (editor.action.showReferences), which needs
    // (uri, position, Location[]).
    const toPosition = (p: any): vscode.Position =>
        p instanceof vscode.Position ? p : new vscode.Position(p.line, p.character);

    const toLocation = (l: any): vscode.Location => {
        if (l instanceof vscode.Location) return l;
        // The provider may return LocationLink[] (targetUri/targetRange) or a
        // plain-JSON Location (uri/range) depending on the round-trip.
        const uri = l.uri ? (l.uri instanceof vscode.Uri ? l.uri : vscode.Uri.parse(String(l.uri)))
                          : vscode.Uri.parse(String(l.targetUri));
        const r = l.range ?? l.targetRange ?? l.targetSelectionRange;
        const range = r instanceof vscode.Range ? r
            : new vscode.Range(r.start.line, r.start.character, r.end.line, r.end.character);
        return new vscode.Location(uri, range);
    };

    const peek = async (uriArg: any, posArg: any, provider: string, noun: string) => {
        const uri = vscode.Uri.parse(String(uriArg));
        const position = toPosition(posArg);

        // showReferences anchors its peek to the ACTIVE editor at `position`,
        // so make sure the target document is open and focused, and the cursor
        // is on the symbol. Without this the peek silently no-ops when the lens
        // is clicked from an unfocused editor.
        const editor = await vscode.window.showTextDocument(uri, { preserveFocus: false });
        editor.selection = new vscode.Selection(position, position);

        const raw = (await vscode.commands.executeCommand<any[]>(provider, uri, position)) ?? [];
        const locations = raw.map(toLocation);
        if (locations.length === 0) {
            vscode.window.setStatusBarMessage(`No ${noun} found`, 3000);
            return;
        }
        await vscode.commands.executeCommand(
            'editor.action.showReferences', uri, position, locations
        );
    };

    context.subscriptions.push(
        vscode.commands.registerCommand('mezze.showReferences', (uri, pos) =>
            peek(uri, pos, 'vscode.executeReferenceProvider', 'references')),
        vscode.commands.registerCommand('mezze.showImplementations', (uri, pos) =>
            peek(uri, pos, 'vscode.executeImplementationProvider', 'implementations')),
        vscode.commands.registerCommand('mezze.showPerformers', (uri, pos) =>
            peek(uri, pos, 'vscode.executeImplementationProvider', 'performers')),
        // The "N branch inputs · sample" lens: run `mez sample --json <fn>` from
        // the project the file belongs to and show the input→output rows as a
        // table in a webview. Only on a click — the lens itself runs nothing.
        vscode.commands.registerCommand('mezze.sample', async (uriArg: any, fn: string) => {
            const file = vscode.Uri.parse(String(uriArg)).fsPath;
            let dir = path.dirname(file);
            while (!fs.existsSync(path.join(dir, 'Mezze.toml')) && path.dirname(dir) !== dir) {
                dir = path.dirname(dir);
            }
            const root = fs.existsSync(path.join(dir, 'Mezze.toml')) ? dir : path.dirname(file);
            // Stable seed on open → reopening a function shows the same table
            // (retained/reproducible); "resample" draws a fresh set. `file` is the
            // module the lens sits in, passed to `mez sample --file` so any open
            // file's functions are sampleable, not only the app module.
            await showSample(root, file, fn, 0, SAMPLE_INITIAL_COUNT);
        })
    );

    // Surface a server crash instead of silently losing all features. The
    // client auto-restarts a few times by default; if it ends up Stopped,
    // tell the user and offer a one-click restart. (Guarded so the normal
    // Starting→Running transition and a user-initiated stop stay quiet.)
    let sawRunning = false;
    context.subscriptions.push(
        client.onDidChangeState((e) => {
            // State enum: Stopped=1, Starting=3, Running=2 (vscode-languageclient).
            if (e.newState === 2 /* Running */) { sawRunning = true; return; }
            if (e.newState === 1 /* Stopped */ && sawRunning) {
                sawRunning = false;
                vscode.window.showWarningMessage(
                    'Mezze Language Server stopped. Language features are unavailable.',
                    'Restart'
                ).then((choice) => {
                    if (choice === 'Restart') {
                        vscode.commands.executeCommand('mezze.restartServer');
                    }
                });
            }
        })
    );

    // Start the client
    client.start().then(() => {
        console.log('Mezze Language Server started');
    }).catch((error) => {
        console.error('Failed to start Mezze Language Server:', error);
        vscode.window.showErrorMessage(
            `Failed to start Mezze Language Server: ${error.message}. ` +
            'Make sure "mez" is installed and available in PATH, or configure "mezze.executablePath" in settings.'
        );
    });
}

// ─── Debugger (DAP) ──────────────────────────────────────────────────────
//
// The debug flow: on F5 we spawn `mez exec --dap=<port> <program>`. That starts
// Mezze's HAND-WRITTEN DAP server (MezzeDapServer in the JVM RuntimeServer),
// which drives GraalVM's Debugger over its OWN socket and blocks execution until
// the client attaches. We point VS Code's DAP client at localhost:<port> (a
// DebugAdapterServer descriptor). Breakpoints / stepping / stack / scopes /
// evaluate flow through the standard DAP wire.
//
// We deliberately do NOT use GraalVM's bundled dap-tool — it hooks the polyglot
// output stream, which `mez` routes through its wire protocol (native CLI ↔ JVM
// child over stdio), corrupting the channel (PROTO_TOO_LARGE) and NPEing on
// connect. Our server uses a separate socket and drives the Debugger API
// directly, so the wire stdio stays clean. See docs/lsp/dap-adapter-design.md.
//
// DAP requires a JIT-capable JVM runtime, so it never runs on the AOT native
// binary — `mez` prints "--dap ignored" there and we surface it.

const DEFAULT_DAP_PORT = 4711;

/** Wait until something is LISTENING on `port`, or reject after `timeoutMs`. */
function waitForPort(port: number, timeoutMs: number, token: vscode.CancellationToken): Promise<void> {
    const start = Date.now();
    return new Promise<void>((resolve, reject) => {
        const attempt = () => {
            if (token.isCancellationRequested) {
                reject(new Error('debug launch cancelled'));
                return;
            }
            const socket = net.connect({ host: '127.0.0.1', port });
            socket.once('connect', () => { socket.end(); resolve(); });
            socket.once('error', () => {
                socket.destroy();
                if (Date.now() - start > timeoutMs) {
                    reject(new Error(`mez DAP server did not open port ${port} within ${timeoutMs}ms`));
                } else {
                    setTimeout(attempt, 150);
                }
            });
        };
        attempt();
    });
}

class MezzeDebugAdapterFactory implements vscode.DebugAdapterDescriptorFactory {
    constructor(private readonly mezPath: string, private readonly output: vscode.OutputChannel) {}

    async createDebugAdapterDescriptor(
        session: vscode.DebugSession,
        _executable: vscode.DebugAdapterExecutable | undefined
    ): Promise<vscode.DebugAdapterDescriptor> {
        const cfg = session.configuration;
        const program: string | undefined = cfg.program;
        if (!program) {
            throw new Error('Mezze debug: no "program" in the launch configuration.');
        }
        const port: number = typeof cfg.port === 'number' ? cfg.port : DEFAULT_DAP_PORT;
        const stopOnEntry: boolean = !!cfg.stopOnEntry;
        const userArgs: string[] = Array.isArray(cfg.args) ? cfg.args : [];
        const cwd: string =
            cfg.cwd || vscode.workspace.getWorkspaceFolder(vscode.Uri.file(program))?.uri.fsPath ||
            path.dirname(program);

        // `mez exec <program> --dap[=port] [--dap-brk]` — dev flags go before the
        // `--` user-arg separator (ExecCommand splits on the first `--`).
        const dapFlag = port === DEFAULT_DAP_PORT ? '--dap' : `--dap=${port}`;
        const args = ['exec', program, dapFlag];
        if (stopOnEntry) { args.push('--dap-brk'); }
        if (userArgs.length > 0) { args.push('--', ...userArgs); }

        this.output.appendLine(`[mezze-debug] ${this.mezPath} ${args.join(' ')}  (cwd=${cwd})`);

        const proc = cp.spawn(this.mezPath, args, { cwd, env: process.env });
        let sawDapListening = false;
        let sawNativeIgnore = false;
        proc.stdout.on('data', (d: Buffer) => this.output.append(d.toString()));
        proc.stderr.on('data', (d: Buffer) => {
            const s = d.toString();
            this.output.append(s);
            if (s.includes('DAP server listening')) { sawDapListening = true; }
            if (s.includes('--dap ignored')) { sawNativeIgnore = true; }
        });
        proc.on('error', (e) => {
            this.output.appendLine(`[mezze-debug] failed to spawn mez: ${e.message}`);
            vscode.window.showErrorMessage(
                `Mezze debug: could not run "${this.mezPath}". ` +
                'Set "mezze.executablePath" or ensure mez is on PATH.'
            );
        });
        // Tear the process down when the debug session ends.
        session.configuration.__mezProc = proc.pid;
        const killOnStop = vscode.debug.onDidTerminateDebugSession((ended) => {
            if (ended.id === session.id) {
                try { proc.kill(); } catch { /* already gone */ }
                killOnStop.dispose();
            }
        });

        // Wait for the DAP server to open its port before handing VS Code the
        // socket. A CancellationTokenSource lets us bail if the process dies.
        const cts = new vscode.CancellationTokenSource();
        proc.on('exit', (code) => {
            this.output.appendLine(`[mezze-debug] mez exited with code ${code}`);
            if (sawNativeIgnore) {
                vscode.window.showWarningMessage(
                    'Mezze debug needs the JVM runtime — the native mez binary ignores --dap. ' +
                    'Run mez from a JDK-backed install to debug.'
                );
            }
            cts.cancel();
        });

        try {
            await waitForPort(port, 15000, cts.token);
        } catch (e: any) {
            proc.kill();
            throw new Error(
                sawDapListening
                    ? `Mezze debug: DAP server started but the client could not connect on port ${port}.`
                    : `Mezze debug: ${e.message}. Check the "Mezze Debug" output for details.`
            );
        }
        return new vscode.DebugAdapterServer(port);
    }
}

/** Fills in defaults so F5 with no launch.json Just Works on the open file. */
class MezzeDebugConfigProvider implements vscode.DebugConfigurationProvider {
    resolveDebugConfiguration(
        folder: vscode.WorkspaceFolder | undefined,
        config: vscode.DebugConfiguration
    ): vscode.ProviderResult<vscode.DebugConfiguration> {
        // No launch.json / empty config → synthesize one for the active file.
        if (!config.type && !config.request && !config.name) {
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document.languageId === 'mezze') {
                config.type = 'mezze';
                config.name = 'Debug Mezze File';
                config.request = 'launch';
                config.program = editor.document.fileName;
                config.stopOnEntry = false;
            }
        }
        if (!config.program) {
            vscode.window.showErrorMessage('Mezze debug: open a .mz file or set "program" in launch.json.');
            return undefined; // abort the launch
        }
        return config;
    }
}

function registerDebugAdapter(context: vscode.ExtensionContext, mezPath: string) {
    const output = vscode.window.createOutputChannel('Mezze Debug');
    context.subscriptions.push(
        output,
        vscode.debug.registerDebugConfigurationProvider('mezze', new MezzeDebugConfigProvider()),
        vscode.debug.registerDebugAdapterDescriptorFactory('mezze', new MezzeDebugAdapterFactory(mezPath, output))
    );
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
