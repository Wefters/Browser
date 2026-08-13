import { definePlugin, registerHook } from "@wefterjs/core";

export type BrowserMode = "webview" | "external";

export interface OpenOptions {
  url: string;
  mode?: BrowserMode;
  id?: string;
  title?: string;
  showToolbar?: boolean;
  showNavigationButtons?: boolean;
  shareButton?: boolean;
  desktopMode?: boolean;
}

export interface OpenResult {
  started: boolean;
}

export interface CloseOptions {
  id?: string;
}

export interface CloseResult {
  closed: boolean;
}

export interface AuthOptions {
  url: string;
  redirectUri: string;
  id?: string;
  ephemeral?: boolean;
}

export interface AuthResult {
  started: boolean;
}

export interface BrowserOpenedEvent {
  url: string;
  mode: BrowserMode;
  id?: string;
}

export interface BrowserClosedEvent {
  reason: string;
  id?: string;
}

export interface BrowserAuthCompletedEvent {
  callbackUrl: string;
  params: Record<string, string>;
  id?: string;
}

export const Browser = {
  ...definePlugin<{
    open: (options: OpenOptions) => Promise<OpenResult>;
    close: (options?: CloseOptions) => Promise<CloseResult>;
    auth: (options: AuthOptions) => Promise<AuthResult>;
  }>("browser", { open: true, close: true, auth: true }),

  onOpened(callback: (data: BrowserOpenedEvent) => void): { remove(): void } {
    return registerHook("browser:opened", callback as (data: unknown) => void);
  },

  onClosed(callback: (data: BrowserClosedEvent) => void): { remove(): void } {
    return registerHook("browser:closed", callback as (data: unknown) => void);
  },

  onAuthCompleted(callback: (data: BrowserAuthCompletedEvent) => void): {
    remove(): void;
  } {
    return registerHook(
      "browser:authCompleted",
      callback as (data: unknown) => void,
    );
  },
};
