/**
 * Opens web pages in an in-app browser or the system browser, and runs OAuth-style sign-in flows that
 * return to the app through a redirect URI.
 */

import { definePlugin, registerHook } from "@wefterjs/core";

/**
 * How to open a page:
 *
 * - `inApp` (the default): the system browser's in-app view, Custom Tabs on Android and Safari's view on
 *   iOS, with the user's own sign-ins, saved passwords and security warnings. The app can't read or script
 *   the page.
 * - `external`: leaves the app for the browser app.
 * - `webview`: the plugin's own web view. Deprecated: for sites you work with, use `openWebWindow()` from
 *   `@wefterjs/core`, which can also talk to the page; for other sites, use `inApp`.
 */
export type BrowserMode = "inApp" | "external" | "webview";

/** What to open and how the in-app browser looks. `id` is echoed back in events. */
export interface OpenOptions {
  url: string;
  mode?: BrowserMode;
  id?: string;
  /** `inApp`: the toolbar's colour, `#RRGGBB`. */
  toolbarColor?: string;
  /** `inApp` (Android) and `webview`: show a share button. */
  shareButton?: boolean;
  /** `webview` only. */
  title?: string;
  /** `webview` only. */
  showToolbar?: boolean;
  /** `webview` only. */
  showNavigationButtons?: boolean;
  /** `webview` only. */
  desktopMode?: boolean;
}

/** Whether the browser was opened. */
export interface OpenResult {
  started: boolean;
}

/** Which browser to close, by the `id` it was opened with. */
export interface CloseOptions {
  id?: string;
}

/** Whether a browser was open and has been closed. */
export interface CloseResult {
  closed: boolean;
}

/**
 * A sign-in flow: the page to open and the redirect URI that ends it. On Android the redirect URI must use
 * the app's own redirect scheme, which {@link Browser.getRedirectUri} builds. `ephemeral` (iOS) uses a
 * private session without shared cookies.
 */
export interface AuthOptions {
  url: string;
  redirectUri: string;
  id?: string;
  ephemeral?: boolean;
}

/** Whether the sign-in flow was started. The outcome arrives as an event. */
export interface AuthResult {
  started: boolean;
}

/** A browser was opened. */
export interface BrowserOpenedEvent {
  url: string;
  mode: BrowserMode;
  id?: string;
}

/** A browser or sign-in flow closed, and why (for example `user_cancelled`). */
export interface BrowserClosedEvent {
  reason: string;
  id?: string;
}

/**
 * A sign-in flow reached its redirect URI. `params` holds the query and fragment parameters of the
 * callback URL, such as `code` and `state`.
 */
export interface BrowserAuthCompletedEvent {
  callbackUrl: string;
  params: Record<string, string>;
  id?: string;
}

/** The app's sign-in redirect scheme. */
export interface RedirectSchemeResult {
  scheme: string;
}

/** Native methods of the plugin. */
const native = definePlugin<{
  /** Opens a URL. */
  open: (options: OpenOptions) => Promise<OpenResult>;
  /** Closes the in-app browser. */
  close: (options?: CloseOptions) => Promise<CloseResult>;
  /**
   * Starts a sign-in flow in a secure browser session (`ASWebAuthenticationSession` on iOS, a Custom Tab on
   * Android). Listen with `Browser.onAuthCompleted` and `Browser.onClosed` for the result.
   */
  auth: (options: AuthOptions) => Promise<AuthResult>;
  /**
   * The app's sign-in redirect scheme: its app id plus `.auth` (for example `com.example.shop.auth`).
   * Development and production builds have different app ids, so each has its own scheme.
   */
  getRedirectScheme: () => Promise<RedirectSchemeResult>;
}>("browser", { open: true, close: true, auth: true, getRedirectScheme: true });

/** The Browser plugin. */
export const Browser = {
  ...native,

  /**
   * Opens a URL, in the system browser's in-app view by default.
   *
   * @see BrowserMode
   */
  open(options: OpenOptions): Promise<OpenResult> {
    if (options.mode === "webview") {
      console.warn(
        '[@wefterjs/browser] mode: "webview" is deprecated and will be removed. Use openWebWindow() from ' +
          '@wefterjs/core for sites you work with, or the default mode ("inApp") for other sites.',
      );
    }
    return native.open(options);
  },

  /**
   * A redirect URI for {@link Browser.auth} on the app's own scheme, such as
   * `com.example.shop.auth://callback`. Register it with the sign-in provider for each build.
   *
   * @param path What follows the scheme; `callback` by default.
   */
  async getRedirectUri(path = "callback"): Promise<string> {
    const { scheme } = await native.getRedirectScheme();
    return `${scheme}://${path.replace(/^\/+/, "")}`;
  },

  /**
   * Calls `callback` when a browser opens.
   *
   * @returns A handle whose `remove()` stops listening.
   */
  onOpened(callback: (data: BrowserOpenedEvent) => void): { remove(): void } {
    return registerHook("browser:opened", callback as (data: unknown) => void);
  },

  /**
   * Calls `callback` when a browser or sign-in flow closes.
   *
   * @returns A handle whose `remove()` stops listening.
   */
  onClosed(callback: (data: BrowserClosedEvent) => void): { remove(): void } {
    return registerHook("browser:closed", callback as (data: unknown) => void);
  },

  /**
   * Calls `callback` when a sign-in flow returns to the app.
   *
   * @returns A handle whose `remove()` stops listening.
   */
  onAuthCompleted(callback: (data: BrowserAuthCompletedEvent) => void): {
    remove(): void;
  } {
    return registerHook("browser:authCompleted", callback as (data: unknown) => void);
  },
};
