const KEY = "astra_gateway_api_key";

export function getApiKey(): string {
  if (typeof window === "undefined") return "";
  return localStorage.getItem(KEY) ?? "";
}

export function setApiKey(key: string) {
  localStorage.setItem(KEY, key);
}

export function clearApiKey() {
  localStorage.removeItem(KEY);
}
