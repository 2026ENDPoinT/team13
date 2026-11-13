import type { Course } from "../data.ts";

export function CoverImage({ course, width = 233, height = 140 }: { course: Course | null | undefined; width?: number; height?: number }) {
  if (!course || !course.cover) return null;
  return <img className="asset asset-cover" src={course.cover} alt="" width={width} height={height} />;
}

export function BrandLogo() {
  return (
    <img
      className="asset asset-logo"
      src="/app/assets/logo/stk-brand-logo-80.png"
      srcSet="/app/assets/logo/stk-brand-logo-64.png 64w, /app/assets/logo/stk-brand-logo-80.png 80w"
      sizes="(max-width: 767px) 32px, 40px"
      width="40"
      height="40"
      alt=""
    />
  );
}

