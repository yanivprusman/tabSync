import Header from "./_components/Header";
import PhonePhotos from "./_components/PhonePhotos";
import PhoneTabs from "./_components/PhoneTabs";

/** The desktop page (signed-in browsers only — see proxy.ts). Photos first: they're what it's mostly for. */
export default function Home() {
  return (
    <main className="mx-auto w-full max-w-4xl px-5 py-10 sm:py-14">
      <Header />
      <PhonePhotos />
      <PhoneTabs />
    </main>
  );
}
