package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Handles persistence of the CodeBuddy provider config (auth token + internet
 * environment). Mirrors {@link ClaudeCliPathHandler}. Persisted via
 * {@link CodemossSettingsService} into the codebuddy section of ~/.codemoss/config.json.
 */
public class CodeBuddyConfigHandler {

    private static final Logger LOG = Logger.getInstance(CodeBuddyConfigHandler.class);

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public CodeBuddyConfigHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get the saved CodeBuddy config (authToken + internetEnv).
     */
    public void handleGetCodeBuddyConfig() {
        CompletableFuture.runAsync(() -> {
            try {
                CodemossSettingsService settings = new CodemossSettingsService();
                String authToken = settings.getCodeBuddyAuthToken();
                String internetEnv = settings.getCodeBuddyInternetEnv();

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("authToken", authToken);
                    response.addProperty("internetEnv", internetEnv);
                    context.callJavaScript("window.updateCodeBuddyConfig", context.escapeJs(gson.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[CodeBuddyConfigHandler] Failed to get config: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Failed to load CodeBuddy config: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CodeBuddyConfigHandler] Unexpected error in handleGetCodeBuddyConfig: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Persist CodeBuddy config. Content JSON: {"authToken": "...", "internetEnv": "ioa"}.
     */
    public void handleSaveCodeBuddyConfig(String content) {
        String authToken = "";
        String internetEnv = "ioa";
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json != null) {
                if (json.has("authToken") && !json.get("authToken").isJsonNull()) {
                    authToken = json.get("authToken").getAsString();
                }
                if (json.has("internetEnv") && !json.get("internetEnv").isJsonNull()) {
                    String env = json.get("internetEnv").getAsString().trim();
                    internetEnv = env.isEmpty() ? "ioa" : env;
                }
            }
        } catch (Exception e) {
            LOG.error("[CodeBuddyConfigHandler] Failed to parse save content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save CodeBuddy config: " + e.getMessage()))
            );
            return;
        }

        final String finalAuthToken = authToken;
        final String finalInternetEnv = internetEnv;

        CompletableFuture.runAsync(() -> {
            try {
                new CodemossSettingsService().saveCodeBuddyConfig(finalAuthToken, finalInternetEnv);
                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("authToken", finalAuthToken);
                    response.addProperty("internetEnv", finalInternetEnv);
                    context.callJavaScript("window.updateCodeBuddyConfig", context.escapeJs(gson.toJson(response)));
                    context.callJavaScript("window.showSwitchSuccess", context.escapeJs("CodeBuddy 配置已保存"));
                });
            } catch (Exception e) {
                LOG.error("[CodeBuddyConfigHandler] Failed to save config: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Failed to save CodeBuddy config: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CodeBuddyConfigHandler] Unexpected error in handleSaveCodeBuddyConfig: " + ex.getMessage(), ex);
            return null;
        });
    }
}
