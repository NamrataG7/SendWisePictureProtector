import SignupForm from './signup-form';

export const metadata = {
  title: 'Create account — SendWise',
};

export default function SignupPage() {
  return (
    <main className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-semibold text-gray-900">SendWise</h1>
          <p className="text-sm text-gray-500 mt-1">Create your parent account</p>
        </div>
        <SignupForm />
        <p className="text-sm text-gray-500 text-center mt-6">
          Already have an account?{' '}
          <a href="/login" className="text-[#6C3FE1] hover:underline font-medium">
            Sign in
          </a>
        </p>
        <p className="text-xs text-gray-400 text-center mt-4">
          By creating an account, you agree to our{' '}
          <a href="/terms" className="underline hover:text-gray-600">Terms</a> and{' '}
          <a href="/privacy" className="underline hover:text-gray-600">Privacy Policy</a>.
        </p>
      </div>
    </main>
  );
}
