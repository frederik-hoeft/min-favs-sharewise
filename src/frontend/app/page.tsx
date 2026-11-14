import { Container, Stack, Text, Title } from "@mantine/core";
import styles from "./page.module.scss";

// Neutral starter page; design candidates replace this composition in Phase B.
export default function Home() {
  return (
    <main className={styles.main}>
      <Container size="sm">
        <Stack align="center" gap="sm">
          <Title order={1}>ShareWise</Title>
          <Text c="dimmed">Group expense sharing and bookkeeping.</Text>
        </Stack>
      </Container>
    </main>
  );
}
