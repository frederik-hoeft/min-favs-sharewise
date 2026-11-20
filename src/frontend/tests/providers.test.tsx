import { Alert, Button, PasswordInput, TextInput, useMantineColorScheme } from "@mantine/core";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Providers } from "@/app/providers";

function ThemeAwareButton({ onClick }: { onClick: () => void }) {
  const { colorScheme } = useMantineColorScheme();

  return (
    <Button onClick={onClick}>
      Active color scheme: {colorScheme}
    </Button>
  );
}

describe("Mantine application provider", () => {
  it("applies shared input radius and semantic feedback styling across the application", () => {
    const { container } = render(
      <Providers>
        <TextInput label="Email" type="email" />
        <PasswordInput label="Password" />
        <Alert color="red" title="Problem">Please retry.</Alert>
        <Alert color="gray" title="Notice">Informational feedback.</Alert>
      </Providers>,
    );
    const email = screen.getByRole("textbox", { name: "Email" });
    const password = screen.getByLabelText("Password");
    // Mantine provides the same radius through Input defaults rather than
    // independently configured props on each page or input type.
    expect(email.closest(".mantine-Input-wrapper")).toHaveStyle("--input-radius: 0.5625rem");
    expect(password.closest(".mantine-Input-wrapper")).toHaveStyle("--input-radius: 0.5625rem");
    expect(email.className).toContain("input");
    const alerts = container.querySelectorAll(".mantine-Alert-root");
    expect(alerts[0]?.getAttribute("style")).toContain("background-color: var(--sw-danger-surface)");
    expect(alerts[1]?.getAttribute("style")).not.toContain("background-color: var(--sw-danger-surface)");
  });

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
