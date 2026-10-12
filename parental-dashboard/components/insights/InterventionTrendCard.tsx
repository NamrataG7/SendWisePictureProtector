'use client';

import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Dot,
} from 'recharts';
import type { TrendPoint } from '@/lib/insights-aggregates';

export default function InterventionTrendCard({ data }: { data: TrendPoint[] }) {
  const hasData = data.some((p) => p.interventions > 0);

  return (
    <div className="bg-white rounded-2xl border border-[#ECEEF3] shadow-sm p-6">
      <h3 className="text-[18px] font-bold text-[#101532] mb-4">
        30-Day Intervention Trend
      </h3>
      <div className="h-[320px] w-full relative">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={data}
            margin={{ top: 12, right: 16, left: 0, bottom: 8 }}
          >
            <defs>
              <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#DCE7FF" stopOpacity={0.9} />
                <stop offset="100%" stopColor="#DCE7FF" stopOpacity={0.4} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="4 4" stroke="#E5E7EB" vertical={false} />
            <XAxis
              dataKey="date"
              tick={{ fill: '#6B7280', fontSize: 12 }}
              tickLine={false}
              axisLine={{ stroke: '#E5E7EB' }}
              label={{
                value: 'Date',
                position: 'insideBottom',
                offset: -4,
                fill: '#6B7280',
                fontSize: 12,
              }}
            />
            <YAxis
              domain={[0, 50]}
              ticks={[0, 10, 20, 30, 40, 50]}
              tick={{ fill: '#6B7280', fontSize: 12 }}
              tickLine={false}
              axisLine={{ stroke: '#E5E7EB' }}
              label={{
                value: 'Interventions',
                angle: -90,
                position: 'insideLeft',
                fill: '#6B7280',
                fontSize: 12,
              }}
            />
            <Tooltip
              contentStyle={{
                background: '#FFFFFF',
                border: '1px solid #ECEEF3',
                borderRadius: 8,
                fontSize: 12,
                color: '#101532',
              }}
            />
            <Area
              type="monotone"
              dataKey="interventions"
              stroke="#2F6BFF"
              strokeWidth={2.5}
              fill="url(#areaFill)"
              dot={<Dot r={5} fill="#2F6BFF" stroke="#2F6BFF" />}
              activeDot={{ r: 6, fill: '#2F6BFF', stroke: '#FFFFFF', strokeWidth: 2 }}
            />
          </AreaChart>
        </ResponsiveContainer>
        {!hasData && (
          <div
            className="absolute inset-0 flex items-center justify-center pointer-events-none"
            aria-hidden="true"
          >
            <span className="text-[13px] font-medium text-[#6B7280] bg-white/70 px-3 py-1 rounded">
              No data yet
            </span>
          </div>
        )}
      </div>
    </div>
  );
}
