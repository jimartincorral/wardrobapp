import { Platform } from 'react-native';
import * as ImageManipulator from 'expo-image-manipulator';
import jpeg from 'jpeg-js';
import { CATEGORIES } from '../constants/categories';
import { GARMENT_COLORS } from '../constants/colors';
import { SEASON_OPTIONS, WEATHER_OPTIONS } from '../constants/style-filters';
import type { SeasonOption, WeatherOption } from '../constants/style-filters';
import { colorDistance } from '../utils/color-distance';

type CategoryKey = keyof typeof CATEGORIES;
type SubcategoryName = (typeof CATEGORIES)[CategoryKey]['subcategories'][number];

export interface GarmentImageSuggestions {
  category?: CategoryKey;
  subcategory?: SubcategoryName;
  colorPrimary?: string;
  seasons: SeasonOption[];
  weather: WeatherOption[];
  confidence: number;
  labels: string[];
}

type AnalysisProgressCallback = (percent: number) => void;

type PredictionLike = {
  className: string;
  probability: number;
};

type SubcategoryRule = {
  category: CategoryKey;
  subcategory: SubcategoryName;
  keywords: string[];
};

const SUBCATEGORY_RULES: SubcategoryRule[] = [
  { category: 'tops', subcategory: 'T-Shirt', keywords: ['t-shirt', 'jersey', 'tee'] },
  { category: 'tops', subcategory: 'Shirt', keywords: ['dress shirt', 'shirt'] },
  { category: 'tops', subcategory: 'Polo', keywords: ['polo'] },
  { category: 'tops', subcategory: 'Sweater', keywords: ['sweatshirt', 'sweater', 'pullover', 'knit'] },
  { category: 'tops', subcategory: 'Hoodie', keywords: ['hoodie'] },
  { category: 'bottoms', subcategory: 'Jeans', keywords: ['jean', 'denim'] },
  { category: 'bottoms', subcategory: 'Pants', keywords: ['trouser', 'pant'] },
  { category: 'bottoms', subcategory: 'Shorts', keywords: ['shorts', 'bermuda'] },
  { category: 'bottoms', subcategory: 'Leggings', keywords: ['leggings', 'tights'] },
  { category: 'dresses', subcategory: 'Midi', keywords: ['dress', 'gown'] },
  { category: 'outerwear', subcategory: 'Coat', keywords: ['overcoat', 'coat', 'trench'] },
  { category: 'outerwear', subcategory: 'Jacket', keywords: ['jacket', 'bomber'] },
  { category: 'midlayer', subcategory: 'Blazer', keywords: ['blazer', 'suit'] },
  { category: 'midlayer', subcategory: 'Poncho', keywords: ['poncho'] },
  { category: 'outerwear', subcategory: 'Cardigan', keywords: ['cardigan'] },
  { category: 'outerwear', subcategory: 'Windbreaker', keywords: ['windbreaker', 'raincoat'] },
  { category: 'shoes', subcategory: 'Sneakers', keywords: ['sneaker', 'running shoe', 'tennis shoe'] },
  { category: 'shoes', subcategory: 'Boots', keywords: ['boot'] },
  { category: 'shoes', subcategory: 'Sandals', keywords: ['sandal', 'flip-flop'] },
  { category: 'shoes', subcategory: 'Heels', keywords: ['high heel', 'stiletto'] },
  { category: 'shoes', subcategory: 'Loafers', keywords: ['loafer', 'moccasin'] },
  { category: 'accessories', subcategory: 'Bag', keywords: ['handbag', 'backpack', 'purse'] },
  { category: 'accessories', subcategory: 'Watch', keywords: ['watch'] },
  { category: 'accessories', subcategory: 'Hat', keywords: ['hat', 'cap'] },
  { category: 'accessories', subcategory: 'Sunglasses', keywords: ['sunglass'] },
  { category: 'activewear', subcategory: 'Workout Top', keywords: ['sports jersey', 'tank suit'] },
  { category: 'activewear', subcategory: 'Track Suit', keywords: ['tracksuit'] },
  { category: 'underwear', subcategory: 'Bra', keywords: ['bra'] },
  { category: 'underwear', subcategory: 'Bodysuit', keywords: ['bodysuit', 'body suit', 'leotard'] },
  { category: 'underwear', subcategory: 'Socks', keywords: ['sock'] },
  { category: 'loungewear', subcategory: 'Pajama Set', keywords: ['pajama set', 'pyjama set', 'sleep set'] },
  { category: 'loungewear', subcategory: 'Pajama Top', keywords: ['pajama top', 'pyjama top', 'sleep shirt'] },
  { category: 'loungewear', subcategory: 'Pajama Bottoms', keywords: ['pajama pants', 'pyjama pants', 'sleep pants', 'sleep shorts'] },
  { category: 'loungewear', subcategory: 'Nightgown', keywords: ['nightgown', 'nightdress'] },
  { category: 'loungewear', subcategory: 'Robe', keywords: ['robe', 'bathrobe'] },
];

const SEASON_RULES: Record<string, SeasonOption[]> = {
  Shorts: ['summer'],
  'Tank Top': ['summer'],
  Sandals: ['summer'],
  Coat: ['winter'],
  Parka: ['winter'],
  Sweater: ['fall', 'winter'],
  Hoodie: ['fall', 'winter'],
  Windbreaker: ['spring', 'fall'],
  Robe: ['fall', 'winter'],
};

const WEATHER_RULES: Record<string, WeatherOption[]> = {
  Shorts: ['hot', 'warm'],
  Sandals: ['hot', 'warm'],
  'Tank Top': ['hot', 'warm'],
  Coat: ['cold'],
  Parka: ['cold', 'snowy'],
  Windbreaker: ['windy', 'rainy'],
  Sweater: ['cool', 'cold'],
  Hoodie: ['cool', 'cold'],
  Robe: ['cool', 'cold'],
};

let modelPromise: Promise<any> | null = null;
let tfPromise: Promise<any> | null = null;

async function getTf() {
  if (!tfPromise) {
    tfPromise = (async () => {
      const tfModule = await import('@tensorflow/tfjs');
      await import('@tensorflow/tfjs-react-native');
      await tfModule.ready();
      await tfModule.setBackend('cpu');
      return tfModule;
    })();
  }
  return tfPromise;
}

function getModel() {
  if (!modelPromise) {
    modelPromise = (async () => {
      const tf = await getTf();
      const mobilenet = await import('@tensorflow-models/mobilenet');
      await tf.ready();
      return mobilenet.load({ version: 2, alpha: 0.5 });
    })();
  }
  return modelPromise;
}

function base64ToBytes(base64: string): Uint8Array {
  if (typeof atob === 'function') {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  if (typeof Buffer !== 'undefined') {
    return Uint8Array.from(Buffer.from(base64, 'base64'));
  }

  throw new Error('No base64 decoder available on this platform');
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b].map(n => Math.max(0, Math.min(255, n)).toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

function nearestGarmentColor(hex: string): string {
  const palette = GARMENT_COLORS.filter(c => c.hex !== '#RAINBOW');
  let nearest = palette[0]?.hex ?? '#000000';
  let nearestDistance = Number.POSITIVE_INFINITY;
  for (const color of palette) {
    const dist = colorDistance(hex, color.hex);
    if (dist < nearestDistance) {
      nearestDistance = dist;
      nearest = color.hex;
    }
  }
  return nearest;
}

function estimateDominantColor(data: Uint8Array): string {
  const decoded = jpeg.decode(data, { useTArray: true });
  const pixels = decoded.data;
  let rTotal = 0;
  let gTotal = 0;
  let bTotal = 0;
  let count = 0;

  for (let i = 0; i < pixels.length; i += 16) {
    const alpha = pixels[i + 3];
    if (alpha < 16) continue;
    rTotal += pixels[i];
    gTotal += pixels[i + 1];
    bTotal += pixels[i + 2];
    count += 1;
  }

  if (count === 0) return '#000000';
  const avgHex = rgbToHex(Math.round(rTotal / count), Math.round(gTotal / count), Math.round(bTotal / count));
  return nearestGarmentColor(avgHex);
}

function predictSubcategory(predictions: PredictionLike[]) {
  for (const prediction of predictions) {
    const label = prediction.className.toLowerCase();
    for (const rule of SUBCATEGORY_RULES) {
      if (rule.keywords.some(keyword => label.includes(keyword))) {
        return {
          category: rule.category,
          subcategory: rule.subcategory,
          confidence: prediction.probability,
        };
      }
    }
  }

  return null;
}

function inferSeasonAndWeather(subcategory?: SubcategoryName) {
  const seasons = new Set<SeasonOption>();
  const weather = new Set<WeatherOption>();

  if (subcategory) {
    for (const season of SEASON_RULES[subcategory] ?? []) seasons.add(season);
    for (const condition of WEATHER_RULES[subcategory] ?? []) weather.add(condition);
  }

  if (seasons.size === 0) seasons.add('all-season');
  if (weather.size === 0) {
    weather.add('warm');
    weather.add('cool');
  }

  const finalSeasons = SEASON_OPTIONS.filter(option => seasons.has(option));
  const finalWeather = WEATHER_OPTIONS.filter(option => weather.has(option));
  return { seasons: finalSeasons, weather: finalWeather };
}

async function imageUriToTensor(imageUri: string, tf: any) {
  const manipulated = await ImageManipulator.manipulateAsync(
    imageUri,
    [{ resize: { width: 224 } }],
    { format: ImageManipulator.SaveFormat.JPEG, compress: 0.9, base64: true }
  );

  if (!manipulated.base64) {
    throw new Error('Could not read image data for analysis');
  }

  const imageData = base64ToBytes(manipulated.base64);
  const decoded = jpeg.decode(imageData, { useTArray: true });
  const rgbaTensor = tf.tensor3d(decoded.data, [decoded.height, decoded.width, 4], 'int32');
  const rgbTensor = tf.slice(rgbaTensor, [0, 0, 0], [decoded.height, decoded.width, 3]);
  rgbaTensor.dispose();
  return rgbTensor;
}

export function isGarmentAnalysisAvailable(): boolean {
  return Platform.OS === 'android';
}

export async function analyzeGarmentImage(
  imageUri: string,
  onProgress?: AnalysisProgressCallback
): Promise<GarmentImageSuggestions | null> {
  if (!isGarmentAnalysisAvailable()) return null;

  let imageTensor: any = null;
  const updateProgress = (percent: number) => {
    if (!onProgress) return;
    onProgress(Math.max(0, Math.min(100, Math.round(percent))));
  };

  try {
    updateProgress(5);
    const tf = await getTf();
    updateProgress(20);
    const model = await getModel();
    updateProgress(35);
    imageTensor = await imageUriToTensor(imageUri, tf);
    updateProgress(55);
    const predictions = await model.classify(imageTensor, 5);
    const subcategoryMatch = predictSubcategory(predictions);
    updateProgress(75);

    const manipulated = await ImageManipulator.manipulateAsync(
      imageUri,
      [{ resize: { width: 64 } }],
      { format: ImageManipulator.SaveFormat.JPEG, compress: 0.8, base64: true }
    );
    const colorPrimary = manipulated.base64
      ? estimateDominantColor(base64ToBytes(manipulated.base64))
      : '#000000';
    updateProgress(90);

    const { seasons, weather } = inferSeasonAndWeather(subcategoryMatch?.subcategory);
    updateProgress(100);
    return {
      category: subcategoryMatch?.category,
      subcategory: subcategoryMatch?.subcategory,
      colorPrimary,
      seasons,
      weather,
      confidence: subcategoryMatch?.confidence ?? 0,
      labels: predictions.map((p: PredictionLike) => p.className),
    };
  } catch (error) {
    console.warn('Garment analysis failed:', error);
    return null;
  } finally {
    if (imageTensor) imageTensor.dispose();
  }
}
