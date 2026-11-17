import { Container } from "@mantine/core";
import { Brand } from "@/components/brand/Brand";
import { ArrowIcon } from "@/components/icons/ArrowIcon";
import styles from "./PublicFooter.module.scss";

export function PublicFooter() {
  return (
    <Container component="footer" size="content" className={styles.footer}>
      <Brand />
      <span>Thoughtfully simple group finances.</span>
      <a href="#top">Back to top <ArrowIcon direction="up" /></a>
    </Container>
  );
}
