import type { ReactElement } from "react";
import type { Metadata } from "next";
import { AuthenticationForm } from "../../_components/AuthenticationForm";

export const metadata: Metadata = { title: "Sign in | ShareWise" };

export default function LoginPage(): ReactElement {
  return <AuthenticationForm mode="login" />;
}
