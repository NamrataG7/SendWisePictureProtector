import Link from 'next/link';

/**
 * Empty-state shown on the parental dashboard home when the signed-in
 * parent has zero children linked. Replaces the fake sample data that
 * previously rendered for brand-new accounts.
 */
export default function EmptyDashboardState() {
  return (
    <div className="max-w-2xl mx-auto mt-8">
      <div className="bg-white rounded-2xl shadow-md border border-gray-200 p-10 text-center">
        <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-purple-100">
          {/* Link / shield icon */}
          <svg
            className="h-10 w-10 text-purple-600"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
            <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
          </svg>
        </div>
        <h2 className="text-2xl font-bold text-gray-900 mb-3">
          No devices linked yet
        </h2>
        <p className="text-gray-600 mb-8 leading-relaxed">
          To start monitoring, ask your child to open the SendWise keyboard
          app → <strong>Settings</strong> → <strong>Parental Link</strong> →{' '}
          <strong>Generate Code</strong>. Then click the button below to enter
          the 6-digit code.
        </p>
        <Link
          href="/pair"
          className="inline-block px-8 py-3 rounded-lg font-semibold text-white shadow-sm transition"
          style={{ backgroundColor: '#6C3FE1' }}
        >
          Link a Child Device
        </Link>
      </div>
    </div>
  );
}
