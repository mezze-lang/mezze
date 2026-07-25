import * as cp from 'child_process';
import * as net from 'net';
import * as path from 'path';
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
            peek(uri, pos, 'vscode.executeImplementationProvider', 'performers'))
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
