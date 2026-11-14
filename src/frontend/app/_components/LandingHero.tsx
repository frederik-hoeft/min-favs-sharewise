import { Button } from "@mantine/core";
import { FinanceWorkspacePreview } from "./FinanceWorkspacePreview";
import styles from "./LandingHero.module.scss";

export function LandingHero() {
  return (
    <section className={styles.hero} aria-labelledby="hero-heading">
      <div className={styles.gridBackdrop} aria-hidden="true" />
      <div className={styles.glow} aria-hidden="true" />
      <div className={styles.inner}>
        <div className={styles.copy}>
          <div className={styles.eyebrow}><span className={styles.pulse} /> THE FUTURE OF SHARED FINANCES <span className={styles.serial}>/ 001</span></div>
          <h1 id="hero-heading">Shared money.<br /><span>Perfectly clear.</span></h1>
          <p className={styles.lead}>Less chasing numbers. More seeing the whole picture. Bring your people, expenses, external settlements, and financial history into one clear place.</p>
          <div className={styles.actions}>
            <Button component="a" href="#platform" size="lg" className={styles.primaryButton} rightSection={<span aria-hidden="true">↗</span>}>Explore ShareWise</Button>
            <Button component="a" href="#how-it-works" size="lg" variant="outline" color="gray" className={styles.secondaryButton}>See how it works <span aria-hidden="true">↓</span></Button>
          </div>
          <div className={styles.miniProof} aria-label="Ideal for shared homes, group trips and project teams">
            <span>BUILT FOR REAL LIFE</span><i aria-hidden="true" /> Shared homes <i aria-hidden="true" /> Group trips <i aria-hidden="true" /> Teams
          </div>
        </div>
        <div className={styles.visual}>
          <div className={styles.frameIndex}>FIG. 01 <span>/ FINANCIAL OVERVIEW</span></div>
          <FinanceWorkspacePreview />
          <span className={styles.orbit} aria-hidden="true" />
        </div>
      </div>
      <div className={styles.bottomLine}>
        <span>ONE SHARED RECORD</span><span>EVERY BALANCE EXPLAINED</span><span>EVERY REVISION RETAINED</span>
      </div>
    </section>
  );
}
