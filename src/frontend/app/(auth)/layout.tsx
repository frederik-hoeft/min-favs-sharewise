import type { ReactNode } from "react";
import Link from "next/link";
import { Brand } from "@/components/brand/Brand";
import { AppearanceMenu } from "@/components/theme/AppearanceMenu";
import { SkipLink } from "@/components/navigation/SkipLink";
import styles from "./layout.module.scss";

/** Authentication is an independent public experience, not the landing-page AppShell. */
export default function AuthLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <div className={styles.shell}>
      <SkipLink href="#main" />
      <header className={styles.header}>
        <Link href="/" className={styles.brandLink} aria-label="ShareWise home"><Brand /></Link>
        <AppearanceMenu />
      </header>
      <main id="main" className={styles.main}>
        {children}
      </main>
    </div>
  );
}
