import Link from 'next/link';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { createClient } from '@/utils/supabase/server';
import { getChildrenForParent } from '@/lib/parent-store';
import { computeInsightsAggregate } from '@/lib/insights-server';
import { emptyInsights, payloadToChartData } from '@/lib/insights-aggregates';
import InsightsGrid from '@/components/insights/InsightsGrid';

export const metadata = {
  title: 'Behavioral Insights — SendWise',
  description:
    'Aggregated behavioral risk indicators from the SendWise parental dashboard.',
};

// Server-rendered on every request — reflects the latest linked children
// and violation writes without a client round-trip.
export const dynamic = 'force-dynamic';

export default async function InsightsPage() {
  const supabase = createClient(await cookies());
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    redirect('/login?callbackUrl=/insights');
  }
  const children = await getChildrenForParent(user.id);

  const hasChildren = children.length > 0;
  const chartData = hasChildren
    ? payloadToChartData(await computeInsightsAggregate(children))
    : emptyInsights();

  return (
    <div className="bg-[#F7F8FB] min-h-screen text-[#101532]">
      {/* Header bar */}
      <header className="w-full bg-white border-b border-[#ECEEF3] px-8 py-5 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div
            className="w-8 h-8 rounded-md flex items-center justify-center text-white font-bold"
            style={{ backgroundColor: '#6C3FE1' }}
            aria-hidden="true"
          >
            S
          </div>
          <div className="leading-tight">
            <div className="text-[22px] font-bold text-[#101532]">SendWise</div>
            <div className="text-[14px] text-[#6B7280]">Parental Dashboard</div>
          </div>
        </div>
        <Link
          href="/"
          className="text-[14px] font-medium text-[#6C3FE1] hover:underline"
        >
          ← Back to Dashboard
        </Link>
      </header>

      {/* Main */}
      <main className="max-w-[1200px] mx-auto px-6 py-8">
        <div className="mb-6">
          <h1 className="text-[28px] font-extrabold text-[#101532]">
            Behavioral Insights
          </h1>
          <p className="text-[14px] text-[#6B7280] mt-1">
            Aggregated behavioral risk indicators. No message content is ever
            displayed.
          </p>
        </div>

        {!hasChildren && (
          <div
            role="status"
            className="mb-6 rounded-2xl border border-[#ECEEF3] bg-white px-6 py-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4"
          >
            <div>
              <div className="text-[16px] font-semibold text-[#101532]">
                No devices linked yet
              </div>
              <p className="text-[14px] text-[#6B7280] mt-1">
                Link a child device to start seeing real behavioral insights.
                Until then, the charts below show zeros.
              </p>
            </div>
            <Link
              href="/pair"
              className="inline-flex items-center justify-center rounded-lg bg-[#6C3FE1] text-white text-[14px] font-medium px-4 py-2 hover:bg-[#5A32C4]"
            >
              Link a device
            </Link>
          </div>
        )}

        <InsightsGrid
          total={chartData.total}
          trend={chartData.trend}
          categoryDistribution={chartData.categoryDistribution}
          severityDistribution={chartData.severityDistribution}
          editedVsSent={chartData.editedVsSent}
        />

        <p className="text-[12px] text-[#6B7280] mt-8">
          Aggregated indicators only. No message content is shown or stored on
          this dashboard.
        </p>
      </main>
    </div>
  );
}
