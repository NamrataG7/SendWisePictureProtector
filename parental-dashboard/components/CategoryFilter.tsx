import { IncidentCategory } from '@/lib/types';

interface CategoryFilterProps {
  selectedCategories: IncidentCategory[];
  onCategoryToggle: (category: IncidentCategory) => void;
}

const categories: { value: IncidentCategory; label: string; icon: string; color: string }[] = [
  { value: 'harassment', label: 'Harassment', icon: '🛡️', color: 'bg-blue-100 text-blue-700 border-blue-300' },
  { value: 'threats', label: 'Threats', icon: '⚠️', color: 'bg-orange-100 text-orange-700 border-orange-300' },
  { value: 'hate_speech', label: 'Hate Speech', icon: '🚫', color: 'bg-purple-100 text-purple-700 border-purple-300' },
  { value: 'sexual_content', label: 'Sexual Content', icon: '🔞', color: 'bg-pink-100 text-pink-700 border-pink-300' },
  { value: 'self_harm', label: 'Self-Harm Risk', icon: '🚨', color: 'bg-red-100 text-red-700 border-red-300' },
  { value: 'sensitive_image', label: 'Sensitive Image', icon: '🖼️', color: 'bg-indigo-100 text-indigo-700 border-indigo-300' },
];

export default function CategoryFilter({ selectedCategories, onCategoryToggle }: CategoryFilterProps) {
  return (
    <div className="mb-6">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Filter by Category:</h3>
      <div className="flex flex-wrap gap-2">
        {categories.map((category) => {
          const isSelected = selectedCategories.includes(category.value);
          return (
            <button
              key={category.value}
              onClick={() => onCategoryToggle(category.value)}
              className={`
                px-4 py-2 rounded-lg font-medium text-sm border-2 transition
                ${isSelected
                  ? category.color + ' shadow-md'
                  : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'
                }
              `}
            >
              <span className="mr-2">{category.icon}</span>
              {category.label}
              {isSelected && ' ✓'}
            </button>
          );
        })}
      </div>
    </div>
  );
}
