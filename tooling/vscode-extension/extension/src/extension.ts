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
