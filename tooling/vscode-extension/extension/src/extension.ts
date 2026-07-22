import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;

export function activate(context: vscode.ExtensionContext) {
    // Get the path to the mez executable
    const config = vscode.workspace.getConfiguration('mezze');
    let mezPath = config.get<string>('executablePath') || 'mez';

    // Allow using a relative path from workspace
    if (!path.isAbsolute(mezPath) && mezPath !== 'mez') {
        const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
        if (workspaceFolder) {
            mezPath = path.join(workspaceFolder.uri.fsPath, mezPath);
        }
    }

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

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
