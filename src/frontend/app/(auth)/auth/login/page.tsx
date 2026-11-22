import type { Metadata } from "next";
import { AuthenticationForm } from "../../_components/AuthenticationForm";

export const metadata: Metadata = { title: "Sign in | ShareWise" };

export default function LoginPage() {
  return <AuthenticationForm mode="login" />;
}
