import type { ReactElement } from "react";
import type { Metadata } from "next";
import { AuthenticationForm } from "../../_components/AuthenticationForm";

export const metadata: Metadata = { title: "Create account | ShareWise" };

export default function RegisterPage(): ReactElement {
  return <AuthenticationForm mode="register" />;
}
