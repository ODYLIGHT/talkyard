/*
 * Copyright (c) 2015, 2017 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


//------------------------------------------------------------------------------
   module debiki2.login {
//------------------------------------------------------------------------------

const d = { i: debiki.internal };


// From before React.js.  Gah! This needs to be refactored :-/  Try to remove this field.
export let anyContinueAfterLoginCallback = null;


export function loginIfNeededReturnToPost(
      loginReason: LoginReason | string, postNr: PostNr, success: () => void, willCompose?: boolean) {
  const anchor = postNr < FirstReplyNr ? '' : (
    // We use 'comment-' for embedded comments, and they start on nr 1 = post 2. [2PAWC0]
    eds.isInEmbeddedCommentsIframe ? '#comment-' + (postNr - 1) : '#post-' + postNr);
  loginIfNeededReturnToAnchor(loginReason, anchor, success, willCompose);
}


export function loginIfNeededReturnToAnchor(
      loginReason: LoginReason | string, anchor: string, success: () => void, willCompose?: boolean) {
  const returnToUrl = makeReturnToPageHashForVerifEmail(anchor);
  success = success || function() {};
  if (ReactStore.getMe().isLoggedIn || (willCompose && ReactStore.mayComposeBeforeSignup())) {
    success();
  }
  else if (eds.isInIframe) {
    // A Chrome 63 bug: https://bugs.chromium.org/p/chromium/issues/detail?id=796912
    // (fixed in Chrome 64) causes a cross-origin-error when the popup, from the *same* origin,
    // attempts to call the callback. So poll for a login cookie instead a bit below,
    // and call `success` there. :- (  // [4PKGTEW20]
    // DO_AFTER 2019-01-01, remove this Chrome 63 bug workaround? Maybe check browser usage stats first?
    //anyContinueAfterLoginCallback = success;

    // Don't open a dialog inside the iframe; open a popup instead.
    // Need to open the popup here immediately, before loading any scripts, because if
    // not done immediately after mouse click, the popup gets blocked (in Chrome at least).
    // And when opening in a popup, we don't need any more scripts here in the main win anyway.
    const url = eds.serverOrigin + '/-/login-popup?mode=' + loginReason +
      '&isInLoginPopup&returnToUrl=' + returnToUrl;
    d.i.createLoginPopup(url);

    let checkLoggedInHandle = setInterval(function() {
      const sessionId = getSetCookie('dwCoSid');
      if (sessionId && checkLoggedInHandle) {
        console.log("Logged in (login-if-needed.ts, isInIframe)");
        clearInterval(checkLoggedInHandle);
        checkLoggedInHandle = null;
        !success || success();  // [4PKGTEW20]
      }
    }, 1000);
    // If the user hasn't logged in within 8 minutes, skip the after-login stuff. The user
    // likely doesn't remember anyway, what hen was trying to do. And might login in another
    // tab instead, when so much time has passed, and just gets confused if something then auto-
    // happens here? (So don't call `success()`.)
    setTimeout(function() {
      if (!checkLoggedInHandle) return;
      clearInterval(checkLoggedInHandle);
      checkLoggedInHandle = null;
    }, 1000*60*8)
  }
  else {
    morebundle.loginIfNeeded(loginReason, returnToUrl, success);
  }
}


function makeReturnToPageHashForVerifEmail(hash) {
  // The magic '__Redir...' string tells the server to use the return-to-URL only if it
  // needs to send an email address verification email (it'd include the return
  // to URL on a welcome page show via a link in the email).
  // '__dwHash__' is an encoded hash that won't be lost when included in a GET URL.
  // The server replaces it with '#' later on.
  // If we're showing embedded comments in an <iframe>, use the embedding page's url.
  const pageUrl = eds.embeddingUrl ? eds.embeddingUrl : window.location.toString();
  let returnToUrl = '_RedirFromVerifEmailOnly_' + pageUrl.replace(/#.*/, '');
  if (hash) {
    hash = hash.replace(/^#/, '');
    returnToUrl += '__dwHash__' + hash;
  }
  return returnToUrl;
}


export function continueAfterLogin(anyReturnToUrl?: string) {
  if (eds.isInLoginWindow) {
    // We're in an admin section login page, or an embedded comments page login popup window.
    if (anyReturnToUrl && anyReturnToUrl.indexOf('_RedirFromVerifEmailOnly_') === -1) {
      window.location.assign(anyReturnToUrl);
    }
    else {
      // (Also see LoginWithOpenIdController, search for [509KEF31].)
      window.opener['debiki'].internal.handleLoginResponse({ status: 'LoginOk' });
      // This should be a login popup. Close the whole popup window.
      close();
    }
  }
  else {
    // We're on a normal page (but not in a login popup window for an embedded comments page).
    // (The login dialogs close themselves when the login event gets fired.)
    debiki2.ReactActions.loadMyself(anyContinueAfterLoginCallback);
  }
}


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
