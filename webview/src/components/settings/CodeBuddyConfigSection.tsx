import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Self-contained CodeBuddy provider config (auth token + internet environment).
 *
 * Persisted via the get_codebuddy_config / save_codebuddy_config Java handlers
 * into the codebuddy section of ~/.codemoss/config.json. Deliberately decoupled
 * from useSettingsBasicActions to keep the change surface small.
 */
export default function CodeBuddyConfigSection() {
  const { t } = useTranslation();
  const [authToken, setAuthToken] = useState('');
  const [internetEnv, setInternetEnv] = useState('ioa');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const handleUpdate = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        setAuthToken(data.authToken || '');
        setInternetEnv(data.internetEnv || 'ioa');
      } catch {
        /* ignore parse errors */
      }
      setSaving(false);
    };
    (window as any).updateCodeBuddyConfig = handleUpdate;
    if (window.sendToJava) {
      window.sendToJava('get_codebuddy_config:');
    }
    return () => {
      (window as any).updateCodeBuddyConfig = undefined;
    };
  }, []);

  const handleSave = useCallback(() => {
    setSaving(true);
    const payload = {
      authToken: (authToken || '').trim(),
      internetEnv: (internetEnv || '').trim() || 'ioa',
    };
    if (window.sendToJava) {
      window.sendToJava(`save_codebuddy_config:${JSON.stringify(payload)}`);
    }
  }, [authToken, internetEnv]);

  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span className="codicon codicon-key" />
        <span style={{ fontWeight: 600 }}>{t('settings.basic.codebuddyConfig.label')}</span>
      </div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
        <input
          type="password"
          placeholder={t('settings.basic.codebuddyConfig.tokenPlaceholder')}
          value={authToken}
          onChange={(e) => setAuthToken(e.target.value)}
          style={{ flex: 1 }}
        />
        <select
          value={internetEnv}
          onChange={(e) => setInternetEnv(e.target.value)}
          style={{ width: 150 }}
        >
          <option value="ioa">iOA (企业)</option>
          <option value="internal">internal (中国版)</option>
          <option value="external">external (海外版)</option>
          <option value="cloudhosted">cloudhosted</option>
        </select>
        <button onClick={handleSave} disabled={saving}>
          {saving && <span className="codicon codicon-loading codicon-modifier-spin" />}
          {t('common.save')}
        </button>
      </div>
      <small style={{ color: 'var(--vscode-descriptionForeground)' }}>
        <span className="codicon codicon-info" /> {t('settings.basic.codebuddyConfig.hint')}
      </small>
    </div>
  );
}
