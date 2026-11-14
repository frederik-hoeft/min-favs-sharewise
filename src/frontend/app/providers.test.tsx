import { Button, useMantineColorScheme } from "@mantine/core";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Providers } from "./providers";

function ThemeAwareButton({ onClick }: { onClick: () => void }) {
  const { colorScheme } = useMantineColorScheme();

  return (
    <Button onClick={onClick}>
      Active color scheme: {colorScheme}
    </Button>
  );
}

describe("Mantine application provider", () => {
  it("supplies a color scheme and renders accessible interactive controls", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(
      <Providers>
        <ThemeAwareButton onClick={onClick} />
      </Providers>,
    );

    await user.click(screen.getByRole("button", { name: /active color scheme:/i }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});
