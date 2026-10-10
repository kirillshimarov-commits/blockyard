//! Microsoft account login for licensed Minecraft.
//!
//! Device code flow: the user opens microsoft.com/link and types a short code, so the
//! launcher needs no redirect URL. The Microsoft token is then exchanged for Xbox Live,
//! XSTS and finally Minecraft tokens. The Azure app id is embedded at build time from
//! MS_CLIENT_ID; the app must have public client flows enabled and be approved by
//! Mojang for the Minecraft API.

use super::{now, paths};
use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const MS_OAUTH: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
const SCOPE: &str = "XboxLive.signin offline_access";

fn client_id() -> Option<&'static str> {
    option_env!("MS_CLIENT_ID").filter(|k| !k.trim().is_empty())
}

pub fn is_available() -> bool {
    client_id().is_some()
}

fn require_client_id() -> Result<&'static str> {
    client_id().ok_or_else(|| anyhow!("Вход через Microsoft не настроен: в этой сборке нет MS_CLIENT_ID"))
}

// ---------- Stored accounts ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub uuid: String,
    pub username: String,
    refresh_token: String,
    access_token: String,
    /// Unix time when the Minecraft token expires.
    expires_at: u64,
}

/// What the UI sees: no tokens.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountInfo {
    pub uuid: String,
    pub username: String,
}

fn file() -> std::path::PathBuf {
    paths::data_dir().join("accounts.json")
}

fn load_all() -> Vec<Account> {
    std::fs::read_to_string(file())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn save_all(accounts: &[Account]) -> Result<()> {
    std::fs::create_dir_all(paths::data_dir())?;
    std::fs::write(file(), serde_json::to_string_pretty(accounts)?)?;
    Ok(())
}

pub fn list() -> Vec<AccountInfo> {
    load_all()
        .into_iter()
        .map(|a| AccountInfo { uuid: a.uuid, username: a.username })
        .collect()
}

pub fn remove(uuid: &str) -> Result<()> {
    let mut all = load_all();
    all.retain(|a| a.uuid != uuid);
    save_all(&all)
}

fn upsert(account: Account) -> Result<()> {
    let mut all = load_all();
    all.retain(|a| a.uuid != account.uuid);
    all.push(account);
    save_all(&all)
}

// ---------- Device code login ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCode {
    #[serde(alias = "user_code")]
    pub user_code: String,
    #[serde(alias = "device_code")]
    pub device_code: String,
    #[serde(alias = "verification_uri")]
    pub verification_uri: String,
    #[serde(default = "default_interval")]
    pub interval: u64,
    #[serde(alias = "expires_in")]
    pub expires_in: u64,
}

fn default_interval() -> u64 {
    5
}

pub async fn start_login() -> Result<DeviceCode> {
    let resp = super::http::client()
        .post(format!("{MS_OAUTH}/devicecode"))
        .form(&[("client_id", require_client_id()?), ("scope", SCOPE)])
        .send()
        .await?;
    let status = resp.status();
    let body: Value = resp.json().await?;
    if !status.is_success() {
        bail!("Microsoft отклонил запрос: {}", body["error_description"].as_str().unwrap_or("неизвестная ошибка"));
    }
    Ok(serde_json::from_value(body)?)
}

#[derive(Deserialize)]
struct MsToken {
    access_token: String,
    refresh_token: String,
}

/// Waits until the user enters the code, then completes the login.
pub async fn finish_login(code: &DeviceCode) -> Result<AccountInfo> {
    let client_id = require_client_id()?;
    let deadline = now() + code.expires_in;
    let mut interval = code.interval.max(1);
    let token = loop {
        tokio::time::sleep(std::time::Duration::from_secs(interval)).await;
        if now() > deadline {
            bail!("Время на ввод кода вышло, попробуйте ещё раз");
        }
        let resp = super::http::client()
            .post(format!("{MS_OAUTH}/token"))
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", code.device_code.as_str()),
            ])
            .send()
            .await?;
        let body: Value = resp.json().await?;
        match body["error"].as_str() {
            None => break serde_json::from_value::<MsToken>(body)?,
            Some("authorization_pending") => continue,
            Some("slow_down") => interval += 5,
            Some("authorization_declined") => bail!("Вход отменён"),
            Some("expired_token") => bail!("Время на ввод кода вышло, попробуйте ещё раз"),
            Some(_) => bail!(
                "Ошибка входа: {}",
                body["error_description"].as_str().unwrap_or("неизвестная ошибка")
            ),
        }
    };
    let account = minecraft_login(&token.access_token, token.refresh_token).await?;
    let info = AccountInfo { uuid: account.uuid.clone(), username: account.username.clone() };
    upsert(account)?;
    Ok(info)
}

// ---------- Token exchange ----------

async fn post_json(url: &str, body: &Value) -> Result<(reqwest::StatusCode, Value)> {
    let resp = super::http::client()
        .post(url)
        .header("Accept", "application/json")
        .json(body)
        .send()
        .await
        .with_context(|| format!("request to {url} failed"))?;
    let status = resp.status();
    let text = resp.text().await?;
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

/// Microsoft access token -> Xbox Live -> XSTS -> Minecraft token and profile.
async fn minecraft_login(ms_access: &str, refresh_token: String) -> Result<Account> {
    let (status, xbl) = post_json(
        "https://user.auth.xboxlive.com/user/authenticate",
        &json!({
            "Properties": { "AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access}") },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }),
    )
    .await?;
    if !status.is_success() {
        bail!("Не удалось войти в Xbox Live ({status})");
    }
    let xbl_token = xbl["Token"].as_str().ok_or_else(|| anyhow!("Xbox Live не вернул токен"))?;
    let uhs = xbl["DisplayClaims"]["xui"][0]["uhs"].as_str().ok_or_else(|| anyhow!("Xbox Live не вернул uhs"))?;

    let (status, xsts) = post_json(
        "https://xsts.auth.xboxlive.com/xsts/authorize",
        &json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }),
    )
    .await?;
    if !status.is_success() {
        let reason = match xsts["XErr"].as_u64() {
            Some(2148916233) => "У этого аккаунта Microsoft нет профиля Xbox. Создайте его на xbox.com и попробуйте снова.",
            Some(2148916235) => "Xbox Live недоступен в вашей стране.",
            Some(2148916236) | Some(2148916237) => "Аккаунту нужна проверка возраста на xbox.com.",
            Some(2148916238) => "Детский аккаунт: его нужно добавить в семью Microsoft взрослым.",
            _ => "Xbox отказал во входе.",
        };
        bail!("{reason}");
    }
    let xsts_token = xsts["Token"].as_str().ok_or_else(|| anyhow!("XSTS не вернул токен"))?;

    let (status, mc) = post_json(
        "https://api.minecraftservices.com/authentication/login_with_xbox",
        &json!({ "identityToken": format!("XBL3.0 x={uhs};{xsts_token}") }),
    )
    .await?;
    if status == reqwest::StatusCode::FORBIDDEN {
        bail!("Mojang ещё не одобрил это приложение для входа в Minecraft. Нужно подать заявку (см. README).");
    }
    if !status.is_success() {
        bail!("Не удалось войти в Minecraft ({status})");
    }
    let mc_token = mc["access_token"].as_str().ok_or_else(|| anyhow!("Minecraft не вернул токен"))?.to_string();
    let expires_in = mc["expires_in"].as_u64().unwrap_or(3600);

    let resp = super::http::client()
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(&mc_token)
        .send()
        .await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        bail!("На этом аккаунте нет купленного Minecraft: Java Edition.");
    }
    let profile: Value = resp.error_for_status()?.json().await?;
    Ok(Account {
        uuid: profile["id"].as_str().ok_or_else(|| anyhow!("нет id профиля"))?.to_string(),
        username: profile["name"].as_str().ok_or_else(|| anyhow!("нет имени профиля"))?.to_string(),
        refresh_token,
        access_token: mc_token,
        expires_at: now() + expires_in,
    })
}

/// Credentials passed to the game.
#[derive(Debug, Clone)]
pub struct Session {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
    pub user_type: &'static str,
}

/// Returns a session for the account, refreshing the tokens when they are about to expire.
pub async fn session(uuid: &str) -> Result<Session> {
    let account = load_all()
        .into_iter()
        .find(|a| a.uuid == uuid)
        .ok_or_else(|| anyhow!("Аккаунт не найден, войдите снова в настройках"))?;
    let account = if account.expires_at > now() + 300 {
        account
    } else {
        let resp = super::http::client()
            .post(format!("{MS_OAUTH}/token"))
            .form(&[
                ("grant_type", "refresh_token"),
                ("client_id", require_client_id()?),
                ("refresh_token", account.refresh_token.as_str()),
                ("scope", SCOPE),
            ])
            .send()
            .await?;
        if !resp.status().is_success() {
            bail!("Сессия Microsoft истекла, войдите в аккаунт заново в настройках");
        }
        let token: MsToken = resp.json().await?;
        let fresh = minecraft_login(&token.access_token, token.refresh_token).await?;
        upsert(fresh.clone())?;
        fresh
    };
    Ok(Session {
        username: account.username,
        uuid: account.uuid,
        access_token: account.access_token,
        user_type: "msa",
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_device_code_response() {
        let code: DeviceCode = serde_json::from_value(json!({
            "user_code": "ABCD1234", "device_code": "xyz", "verification_uri": "https://www.microsoft.com/link",
            "expires_in": 900, "interval": 5, "message": "..."
        }))
        .unwrap();
        assert_eq!(code.user_code, "ABCD1234");
        assert_eq!(code.interval, 5);
    }
}
