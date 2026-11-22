import type { Metadata } from "next";
import { AuthenticationForm } from "../../_components/AuthenticationForm";

export const metadata: Metadata = { title: "Create account | ShareWise" };

export default function RegisterPage() {
  return <AuthenticationForm mode="register" />;
}
