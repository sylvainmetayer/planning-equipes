<#--
  Cadre HTML commun à tous les e-mails du realm. Le contenu (<#nested>) est
  celui du thème de base : un <p> ou deux et un lien, traduits par Keycloak.

  Tout est en style inline et en tableaux : c'est ce que lisent les clients
  de messagerie, qui ignorent une feuille de style liée et souvent un <style>.
  Le logo est un PNG (les SVG sont retirés par Gmail, entre autres), servi par
  Keycloak lui-même depuis brand/ — en production, le dossier monté du client.
-->
<#macro emailLayout>
<#assign accent = (properties.brandAccent?has_content && properties.brandAccent?matches("^#[0-9a-fA-F]{3,8}$"))?then(properties.brandAccent, "#3a6ea5")>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="light">
</head>
<body style="margin:0;padding:0;background:#eef2f7;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#eef2f7;">
    <tr>
        <td align="center" style="padding:32px 16px;">
            <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;">
                <tr>
                    <td style="height:6px;background:${accent};font-size:0;line-height:0;">&nbsp;</td>
                </tr>
                <tr>
                    <td align="center" style="padding:28px 32px 4px;">
                        <img src="${url.resourcesUrl}/brand/logo-email.png" alt="${realmName}" height="48" style="height:48px;max-width:100%;border:0;">
                    </td>
                </tr>
                <tr>
                    <td style="padding:16px 32px 32px;color:#1f2933;font-size:16px;line-height:1.55;">
                        <#nested>
                    </td>
                </tr>
                <tr>
                    <td style="padding:16px 32px;border-top:1px solid #e4eaf1;color:#6b7280;font-size:12px;line-height:1.4;">
                        ${realmName}
                    </td>
                </tr>
            </table>
        </td>
    </tr>
</table>
</body>
</html>
</#macro>
