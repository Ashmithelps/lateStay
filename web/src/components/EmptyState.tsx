import { ButtonLink } from "./Button";

export function EmptyState({ title, body, actions }: {
  title: string;
  body: string;
  actions: { label: string; href: string }[];
}) {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h2 className="text-22 font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-16 text-ink-secondary">{body}</p>
      <div className="mt-6 flex flex-wrap justify-center gap-3">
        {actions.map((a) => (
          <ButtonLink key={a.href} href={a.href} variant="outline">
            {a.label}
          </ButtonLink>
        ))}
      </div>
    </div>
  );
}
