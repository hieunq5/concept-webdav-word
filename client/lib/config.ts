export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export const DOCUMENT_FILENAME =
  process.env.NEXT_PUBLIC_DOCUMENT_FILENAME ?? "sample.docx";

export function webdavUrl(filename: string): string {
  return `${API_BASE_URL}/webdav/${filename}`;
}

export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`;
}
