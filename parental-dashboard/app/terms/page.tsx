import fs from 'fs';
import path from 'path';

export default function TermsPage() {
  // Read the Terms of Service from the root directory
  const termsPath = path.join(process.cwd(), '..', 'TERMS_OF_SERVICE.md');
  let termsContent = '';

  try {
    termsContent = fs.readFileSync(termsPath, 'utf-8');
  } catch (error) {
    termsContent = 'Terms of Service document not found.';
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
      <div className="max-w-4xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="mb-8">
          <a
            href="/"
            className="text-blue-600 hover:text-blue-700 text-sm mb-4 inline-block font-medium"
          >
            ← Back to Dashboard
          </a>
          <h1 className="text-4xl font-bold text-gray-900 mt-4">Terms of Service</h1>
          <p className="text-gray-600 mt-2">SendWise Parental Dashboard</p>
        </div>

        {/* Content */}
        <div className="bg-white rounded-lg shadow-md p-8">
          <div className="prose prose-slate max-w-none">
            <pre className="whitespace-pre-wrap font-sans text-gray-700 text-sm leading-relaxed">
              {termsContent}
            </pre>
          </div>
        </div>

        {/* Footer */}
        <div className="mt-6 text-center">
          <p className="text-sm text-gray-600">
            Questions? Contact us or{' '}
            <a href="/privacy" className="text-blue-600 hover:text-blue-700 underline">
              view our Privacy Policy
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
