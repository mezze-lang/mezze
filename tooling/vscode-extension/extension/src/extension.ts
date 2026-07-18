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
            fileEvents: vscode.workspace.createFileSystemWatcher('**/Mezze.toml')
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
        })
    );

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
