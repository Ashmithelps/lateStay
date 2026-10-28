export function TypingDots() {
  return (
    <span role="status" aria-label="The front desk is typing" className="inline-flex items-center gap-1 py-2">
      {[0, 150, 300].map((delay) => (
        <span
          key={delay}
          className="size-2 rounded-full bg-ink-secondary animate-typing"
          style={{ animationDelay: `${delay}ms` }}
        />
      ))}
    </span>
  );
}
