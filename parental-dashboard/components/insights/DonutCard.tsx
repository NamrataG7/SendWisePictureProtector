'use client';

import { PieChart, Pie, Cell, ResponsiveContainer, LabelProps } from 'recharts';
import type { DonutSlice } from '@/lib/insights-aggregates';

interface DonutCardProps {
  title: string;
  slices: DonutSlice[];
  total: number;
  showTotal?: boolean;
}

interface SliceLabelProps extends LabelProps {
  cx?: number;
  cy?: number;
  midAngle?: number;
  innerRadius?: number;
  outerRadius?: number;
  value?: number;
}

function renderSliceLabel(props: SliceLabelProps): React.ReactNode {
  const {
    cx = 0,
    cy = 0,
    midAngle = 0,
    innerRadius = 0,
    outerRadius = 0,
    value = 0,
  } = props;
  if (!value || value <= 0) return null;
  const RADIAN = Math.PI / 180;
  const radius = innerRadius + (outerRadius - innerRadius) * 0.5;
  const x = cx + radius * Math.cos(-midAngle * RADIAN);
  const y = cy + radius * Math.sin(-midAngle * RADIAN);
  return (
    <text
      x={x}
      y={y}
      fill="#FFFFFF"
      textAnchor="middle"
      dominantBaseline="central"
      fontSize={14}
      fontWeight={700}
    >
      {`${value}%`}
    </text>
  );
}

export default function DonutCard({
  title,
  slices,
  total,
  showTotal = true,
}: DonutCardProps) {
  const sumValues = slices.reduce((a, s) => a + (s.value ?? 0), 0);
  const isEmpty = sumValues <= 0;

  // For empty state, render a single gray disk so the donut shape is preserved.
  const emptySlices: DonutSlice[] = [
    { name: 'No data', value: 1, color: '#E5E7EB' },
  ];
  const chartSlices = isEmpty ? emptySlices : slices;

  return (
    <div className="bg-white rounded-2xl border border-[#ECEEF3] shadow-sm p-6">
      <h3 className="text-[18px] font-bold text-[#101532] mb-4">{title}</h3>
      <div className="flex items-center gap-6">
        {/* Donut */}
        <div className="h-[260px] w-[260px] flex-shrink-0 relative">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={chartSlices}
                dataKey="value"
                nameKey="name"
                innerRadius="55%"
                outerRadius="85%"
                startAngle={90}
                endAngle={-270}
                stroke="#FFFFFF"
                strokeWidth={2}
                labelLine={false}
                label={isEmpty ? undefined : renderSliceLabel}
                isAnimationActive={false}
              >
                {chartSlices.map((s) => (
                  <Cell key={s.name} fill={s.color} />
                ))}
              </Pie>
            </PieChart>
          </ResponsiveContainer>
          {isEmpty && (
            <div
              className="absolute inset-0 flex items-center justify-center pointer-events-none"
              aria-hidden="true"
            >
              <span className="text-[13px] font-medium text-[#6B7280]">
                No data yet
              </span>
            </div>
          )}
        </div>

        {/* Legend + total */}
        <div className="flex-1 min-w-0">
          <ul className="space-y-2">
            {slices.map((s) => (
              <li
                key={s.name}
                className="flex items-center justify-between gap-3 text-[14px]"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span
                    className="inline-block w-3 h-3 rounded-sm flex-shrink-0"
                    style={{ backgroundColor: s.color }}
                    aria-hidden="true"
                  />
                  <span className="font-medium text-[#101532] truncate">
                    {s.name}
                  </span>
                </span>
                <span className="font-bold text-[#101532] tabular-nums">
                  {s.value}%
                </span>
              </li>
            ))}
          </ul>

          {showTotal && (
            <div className="mt-6">
              <div className="text-[13px] font-medium text-[#6B7280]">
                Total Interventions
              </div>
              <div
                className="text-[28px] font-extrabold text-[#101532] leading-tight"
              >
                {total}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
