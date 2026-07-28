package com.github.claudecodegui.provider.codebuddy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CodeBuddy SDK bridge.
 *
 * Mirrors CodexSDKBridge's per-process channel-manager.js flow, but drives the
 * CodeBuddy Agent SDK (@tencent-ai/agent-sdk) via the "codebuddy" provider.
 * Auth (CODEBUDDY_AUTH_TOKEN / CODEBUDDY_INTERNET_ENVIRONMENT) is passed through
 * stdin and applied inside the Node message-service, so no secrets leak into the
 * process environment.
 *
 * Output uses the same tagged protocol as Claude/Codex, so the existing UI
 * rendering pipeline works unchanged.
 */
public class CodeBuddySDKBridge extends BaseSDKBridge {

    public CodeBuddySDKBridge() {
        super(CodeBuddySDKBridge.class);
    }

    @Override
    protected String getProviderName() {
        return "codebuddy";
    }

    @Override
    protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        // CodeBuddy auth is delivered via stdin (authToken/internetEnv) and applied
        // inside the Node message-service; no provider-specific env is needed here.
    }

    // ============================================================================
    // Stream parsing (tag protocol shared with Claude/Codex)
    // ============================================================================

    @Override
    protected void processOutputLine(
            String line,
            MessageCallback callback,
            SDKResult result,
            StringBuilder assistantContent,
            AtomicBoolean hadSendError,
            AtomicReference<String> lastNodeError
    ) {
        if (line.contains("[DEBUG]")) {
            LOG.debug("[CodeBuddy] " + line);
        }

        if (line.startsWith("[MESSAGE_START]")) {
            callback.onMessage("message_start", "");
        } else if (line.startsWith("[STREAM_START]")) {
            callback.onMessage("stream_start", "");
        } else if (line.startsWith("[STREAM_END]")) {
            callback.onMessage("stream_end", "");
        } else if (line.startsWith("[MESSAGE_END]")) {
            callback.onMessage("message_end", "");
        } else if (line.startsWith("[SESSION_ID]")) {
            String sessionId = line.substring("[SESSION_ID]".length()).trim();
            callback.onMessage("session_id", sessionId);
        } else if (line.startsWith("[MESSAGE]")) {
            String jsonStr = line.substring("[MESSAGE]".length()).trim();
            try {
                JsonObject msg = gson.fromJson(jsonStr, JsonObject.class);
                if (msg != null) {
                    String msgType = msg.has("type") && !msg.get("type").isJsonNull()
                            ? msg.get("type").getAsString()
                            : "unknown";

                    result.messages.add(msg);

                    if ("assistant".equals(msgType)) {
                        try {
                            String extracted = extractAssistantText(msg);
                            if (extracted != null && !extracted.isEmpty()) {
                                assistantContent.append(extracted);
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    callback.onMessage(msgType, jsonStr);
                }
            } catch (Exception ignored) {
            }
        } else if (line.startsWith("[CONTENT_DELTA]")) {
            String delta = decodeJsonStringPayload(line.substring("[CONTENT_DELTA]".length()));
            assistantContent.append(delta);
            callback.onMessage("content_delta", delta);
        } else if (line.startsWith("[THINKING_DELTA]")) {
            String delta = decodeJsonStringPayload(line.substring("[THINKING_DELTA]".length()));
            callback.onMessage("thinking_delta", delta);
        } else if (line.startsWith("[CONTENT]")) {
            String content = line.substring("[CONTENT]".length()).trim();
            if (!assistantContent.toString().contains(content)) {
                assistantContent.append(content);
            }
            callback.onMessage("content", content);
        } else if (line.startsWith("[SEND_ERROR]")) {
            String jsonStr = line.substring("[SEND_ERROR]".length()).trim();
            String errorMessage = jsonStr;
            try {
                JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                if (obj.has("error")) {
                    errorMessage = obj.get("error").getAsString();
                }
            } catch (Exception ignored) {
            }
            hadSendError.set(true);
            result.success = false;
            result.error = errorMessage;
            callback.onError(errorMessage);
        }
    }

    private String decodeJsonStringPayload(String rawPayload) {
        String jsonStr = rawPayload.startsWith(" ") ? rawPayload.substring(1) : rawPayload;
        try {
            String decoded = gson.fromJson(jsonStr, String.class);
            return decoded != null ? decoded : "";
        } catch (Exception e) {
            LOG.warn("[CodeBuddySDKBridge] Failed to decode JSON string payload, falling back to raw: " + e.getMessage());
            return jsonStr;
        }
    }

    private String extractAssistantText(JsonObject msg) {
        try {
            if (!msg.has("message") || !msg.get("message").isJsonObject()) {
                return "";
            }
            JsonObject message = msg.getAsJsonObject("message");
            if (!message.has("content") || !message.get("content").isJsonArray()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : message.getAsJsonArray("content")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject block = el.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString())
                        && block.has("text") && block.get("text").isJsonPrimitive()) {
                    sb.append(block.get("text").getAsString());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ============================================================================
    // Message sending
    // ============================================================================

    /**
     * Send a message to CodeBuddy (streaming response).
     *
     * @param channelId       Channel identifier
     * @param message         User message
     * @param sessionId       CodeBuddy session id to resume (empty on first turn)
     * @param cwd             Working directory
     * @param permissionMode  Permission mode (default/acceptEdits/bypassPermissions/plan)
     * @param model           Model override (optional)
     * @param reasoningEffort Reasoning effort (optional)
     * @param authToken       CODEBUDDY_AUTH_TOKEN (iOA OAuth token)
     * @param internetEnv     CODEBUDDY_INTERNET_ENVIRONMENT (ioa/internal/...)
     * @param callback        Message callback
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            String permissionMode,
            String model,
            String reasoningEffort,
            String authToken,
            String internetEnv,
            MessageCallback callback
    ) {
        return CompletableFuture.supplyAsync(() -> {
            SDKResult result = new SDKResult();
            StringBuilder assistantContent = new StringBuilder();
            AtomicReference<String> lastNodeError = new AtomicReference<>(null);
            AtomicBoolean hadSendError = new AtomicBoolean(false);

            try {
                if (authToken == null || authToken.trim().isEmpty()) {
                    String error = "CodeBuddy auth token is not configured. Set CODEBUDDY_AUTH_TOKEN in the CodeBuddy provider settings.";
                    result.success = false;
                    result.error = error;
                    callback.onError(error);
                    return result;
                }

                String node = nodeDetector.findNodeExecutable();
                File bridgeDir = getDirectoryResolver().findSdkDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    result.success = false;
                    result.error = "Bridge directory not ready or invalid";
                    return result;
                }

                JsonObject stdinInput = new JsonObject();
                stdinInput.addProperty("message", message != null ? message : "");
                stdinInput.addProperty("sessionId", sessionId != null ? sessionId : "");
                stdinInput.addProperty("cwd", cwd != null ? cwd : "");
                stdinInput.addProperty("permissionMode", permissionMode != null ? permissionMode : "");
                stdinInput.addProperty("model", model != null ? model : "");
                stdinInput.addProperty("reasoningEffort", reasoningEffort != null ? reasoningEffort : "");
                stdinInput.addProperty("authToken", authToken);
                stdinInput.addProperty("internetEnv", internetEnv != null ? internetEnv : "");
                String stdinJson = gson.toJson(stdinInput);

                String scriptPath = new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath();
                List<String> command = NodeDetector.buildNodeScriptCommand(node, scriptPath);
                command.add("codebuddy");
                command.add("send");

                File processTempDir = processManager.prepareClaudeTempDir();

                ProcessBuilder pb = new ProcessBuilder(command);
                if (cwd != null && !cwd.isEmpty() && !"undefined".equals(cwd) && !"null".equals(cwd)) {
                    File userWorkDir = new File(cwd);
                    if (userWorkDir.exists() && userWorkDir.isDirectory()) {
                        pb.directory(userWorkDir);
                    } else {
                        pb.directory(bridgeDir);
                    }
                } else {
                    pb.directory(bridgeDir);
                }

                Map<String, String> env = pb.environment();
                envConfigurator.configureTempDir(env, processTempDir);
                pb.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(pb, node);

                LOG.info("[CodeBuddy] Command: " + String.join(" ", command));

                Process process = null;
                try {
                    process = pb.start();
                    processManager.registerProcess(channelId, process);

                    try (OutputStream stdin = process.getOutputStream()) {
                        stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                    } catch (Exception e) {
                        LOG.warn("[CodeBuddy] Failed to write stdin: " + e.getMessage());
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("[UNCAUGHT_ERROR]")
                                    || line.startsWith("[UNHANDLED_REJECTION]")
                                    || line.startsWith("[COMMAND_ERROR]")) {
                                LOG.warn("[Node.js ERROR] " + line);
                                lastNodeError.set(line);
                            }
                            processOutputLine(line, callback, result, assistantContent, hadSendError, lastNodeError);
                        }
                    }

                    process.waitFor();
                    int exitCode = process.exitValue();
                    boolean wasInterrupted = processManager.wasInterrupted(channelId);

                    result.finalResult = assistantContent.toString();
                    result.messageCount = result.messages.size();

                    if (wasInterrupted) {
                        result.success = false;
                        result.error = "User interrupted";
                        callback.onComplete(result);
                    } else if (!hadSendError.get()) {
                        result.success = exitCode == 0;
                        if (!result.success) {
                            String errorMsg = "CodeBuddy process exited with code: " + exitCode;
                            String nodeErr = lastNodeError.get();
                            if (nodeErr != null && !nodeErr.isEmpty()) {
                                errorMsg = errorMsg + " | Last error: " + nodeErr;
                            }
                            result.error = errorMsg;
                        }
                        callback.onComplete(result);
                    } else {
                        // SEND_ERROR already reported via callback.onError; still notify completion.
                        callback.onComplete(result);
                    }
                } catch (Exception e) {
                    LOG.error("[CodeBuddy] Send failed", e);
                    result.success = false;
                    result.error = e.getMessage();
                    callback.onComplete(result);
                } finally {
                    processManager.unregisterProcess(channelId, process);
                    processManager.waitForProcessTermination(process);
                }
                return result;
            } catch (Exception e) {
                LOG.error("[CodeBuddy] sendMessage failed", e);
                result.success = false;
                result.error = e.getMessage();
                callback.onComplete(result);
                return result;
            }
        });
    }
}
