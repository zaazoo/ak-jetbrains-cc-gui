/**
 * CodeBuddy channel command handler – keeps CodeBuddy specific logic separated,
 * mirroring codex-channel.js. Dispatched from daemon.js when method == "codebuddy.*".
 */
import { sendMessage as codebuddySendMessage } from '../services/codebuddy/message-service.js';

/**
 * Execute a CodeBuddy command.
 * @param {string} command
 * @param {string[]} args
 * @param {object|null} stdinData
 */
export async function handleCodeBuddyCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      const d = stdinData || {};
      await codebuddySendMessage({
        message: d.message,
        sessionId: d.sessionId || d.threadId || '',
        cwd: d.cwd || '',
        permissionMode: d.permissionMode || '',
        model: d.model || '',
        reasoningEffort: d.reasoningEffort || '',
        authToken: d.authToken || '',
        internetEnv: d.internetEnv || '',
        extraEnv: d.extraEnv || null,
      });
      break;
    }

    default:
      throw new Error(`Unknown CodeBuddy command: ${command}`);
  }
}

export function getCodeBuddyCommandList() {
  return ['send'];
}
