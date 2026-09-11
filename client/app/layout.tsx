import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Attachment Inline Editor",
  description: "Open a .docx in desktop Word via WebDAV and auto-save back",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
