import SignIn from "../_components/SignIn";

/** What proxy.ts shows at `/` to a browser that hasn't signed in. */
export default function SignInPage() {
  return <SignIn linkExpired={false} />;
}
