<#--
  L'écran de saisie du code. Il hérite du gabarit du thème actif plutôt que de
  poser son propre HTML : une page qui ne ressemble pas aux autres écrans de
  connexion est exactement ce qu'imite une page d'hameçonnage.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("codeEmailTitre")}
    <#elseif section = "form">
        <form id="kc-code-email-form" class="${properties.kcFormClass!}"
              action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="code" class="${properties.kcLabelClass!}">${msg("codeEmailLabel")}</label>
                <#--
                  inputmode/autocomplete : le clavier numérique sur téléphone, et
                  le remplissage automatique du code reçu, que la plupart des
                  systèmes proposent dès qu'ils reconnaissent « one-time-code ».
                -->
                <input id="code" name="code" type="text" autofocus
                       inputmode="numeric" pattern="[0-9]*" maxlength="6"
                       autocomplete="one-time-code"
                       class="${properties.kcInputClass!}"/>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}"
                       type="submit" value="${msg("doSubmit")}"/>
            </div>
        </form>
    <#elseif section = "info">
        ${msg("codeEmailAide")}
    </#if>
</@layout.registrationLayout>
