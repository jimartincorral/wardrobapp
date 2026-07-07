const en = {
  // Tab navigation
  tabs: {
    home: 'Home',
    wardrobe: 'Wardrobe',
    outfits: 'Outfits',
    analytics: 'Analytics',
  },

  // Root layout screen titles
  screens: {
    addGarment: 'Add Garment',
    editGarment: 'Edit Garment',
    garmentDetails: 'Garment Details',
    outfitDetails: 'Outfit Details',
    settings: 'Settings',
  },

  // Home screen
  home: {
    title: 'My Wardrobe',
    subtitle: 'Keep track of everything you wear',
    stats: {
      items: 'Items',
      archived: 'Archived',
    },
    addButton: '+ Add New Garment',
    actions: {
      outfitIdeas: 'Get Outfit Ideas',
      outfitDesc: 'AI-powered suggestions',
      analytics: 'View Analytics',
      analyticsDesc: 'Wardrobe overview',
    },
  },

  // Wardrobe screen
  wardrobe: {
    searchPlaceholder: 'Search garments...',
    showFilters: 'Show filters',
    hideFilters: 'Hide filters',
    clearAll: 'Clear all',
    brandPlaceholder: 'Filter by brand',
    sizePlaceholder: 'Filter by size',
    filterAll: 'All',
    sort: {
      newest: 'Newest',
      oldest: 'Oldest',
    },
    itemsCount: '%{count} items in wardrobe',
    emptyLoading: 'Loading...',
    emptyState: 'No garments yet. Add your first one!',
    emptyFiltered: 'No garments match these filters.',
  },

  // Outfits screen
  outfits: {
    title: 'Outfit Suggestions',
    subtitle: 'Rate suggestions to help the app learn your style',
    filters: {
      season: 'Season',
      weather: 'Weather',
      occasion: 'Occasion',
      any: 'Any',
    },
    filterValues: {
      season: {
        spring: 'Spring',
        summer: 'Summer',
        fall: 'Fall',
        winter: 'Winter',
        'all-season': 'All Season',
      },
      weather: {
        hot: 'Hot',
        warm: 'Warm',
        cool: 'Cool',
        cold: 'Cold',
        rainy: 'Rainy',
        snowy: 'Snowy',
        windy: 'Windy',
      },
      occasion: {
        casual: 'Casual',
        work: 'Work',
        formal: 'Formal',
        sport: 'Sport',
        lounge: 'Lounge',
        party: 'Party',
        travel: 'Travel',
      },
    },
    generateButton: '✨ Generate Outfit Ideas',
    generating: 'Generating...',
    saveOutfit: 'Save Outfit',
    emptyHint: 'Add garments to your wardrobe, then generate outfit ideas!',
    emptyNudge: 'Try adding tags like season, weather, and occasion to improve suggestions.',
    savedTitle: 'Saved Outfits',
    itemsCount: '%{count} items',
    pin: 'Pin',
    unpin: 'Unpin',
    saved: 'Saved!',
    savedMsg: 'Outfit saved to your collection.',
  },

  // Analytics screen
  analytics: {
    totalItems: 'Total Items',
    archivedItems: 'Archived Items',
    categoryBreakdown: 'Category Breakdown',
    emptyBreakdown: 'Add garments to see breakdown',
    lifespanTitle: 'Garment Lifespan',
    emptyLifespan: 'Mark garments unavailable to see lifespan stats.',
    getStartedTitle: 'Start building insights',
    getStartedBody: 'Add garments to start building your wardrobe overview.',
  },

  // Add garment screen
  addGarment: {
    tapToChange: 'Tap to change',
    takePhoto: 'Take Photo',
    chooseGallery: 'Choose from Gallery',
    importFromUrl: 'Import from URL',
    importUrlCta: 'Paste a product link to pull in its images',
    importUrlButton: 'Import URL',
    importUrlLoading: 'Importing images from link...',
    importUrlSource: 'Imported from %{brand}',
    importUrlWarnings: 'Import notes: %{warnings}',
    importUrlSuccess: '%{count} images imported from the product page.',
    removeBackground: 'Remove Background',
    undoBackground: 'Undo background removal',
    analyzePhoto: 'Analyze Photo',
    analyzingPhoto: 'Analyzing photo for auto-suggestions...',
    labels: {
      photos: 'Photos *',
      category: 'Category *',
      type: 'Type',
      tags: 'Tags',
      season: 'Seasons',
      weather: 'Weather',
      occasion: 'Occasions',
      colors: 'Colors *',
      primaryColor: 'Primary Color *',
      brand: 'Brand',
      size: 'Size',
    },
    placeholders: {
      brand: 'e.g., Zara, Nike',
      customSize: 'Or type custom size',
      productUrl: 'https://shop.example.com/product',
    },
    saveButton: 'Save Garment',
    saveChanges: 'Save Changes',
    cancelButton: 'Cancel',
    addAnotherPhoto: 'Add Another Photo',
    removePhoto: 'Remove Photo',
    useAsCover: 'Use as Cover',
    errors: {
      permissionPhotos: 'Please allow access to your photos.',
      permissionCamera: 'Please allow access to the camera.',
      photoRequired: 'Please add a photo of the garment.',
      saveFailed: 'Failed to save garment.',
      invalidUrl: 'Enter a valid product URL.',
      importFailed: 'Could not import that product link.',
      permissionTitle: 'Permission needed',
      photoRequiredTitle: 'Photo required',
      importTitle: 'URL Import',
      errorTitle: 'Error',
    },
    bgRemoval: {
      errorTitle: 'Background Removal',
      failed: 'Failed to remove background',
      unavailable: 'Background removal is not available on this platform yet.',
    },
  },

  // Garment detail screen
  garmentDetail: {
    loading: 'Loading...',
    props: {
      color: 'Color',
      colors: 'Colors',
      size: 'Size',
      seasons: 'Seasons',
      weather: 'Weather',
      occasions: 'Occasions',
      added: 'Added',
    },
    tags: 'Tags',
    markUnavailable: 'Mark as Unavailable',
    markAvailable: 'Mark as Available',
    editGarment: 'Edit Garment',
    deleteGarment: 'Delete Garment',
    unavailableSince: 'Unavailable since %{date}',
    alerts: {
      markUnavailableTitle: 'Mark as Unavailable',
      markUnavailableMsg: 'This will mark the garment as donated/sold/damaged. You can undo this later.',
      markUnavailableConfirm: 'Mark Unavailable',
      deleteTitle: 'Delete Garment',
      deleteMsg: 'This will permanently delete this garment.',
      deleteConfirm: 'Delete',
      cancel: 'Cancel',
    },
  },

  // Outfit detail screen
  outfitDetail: {
    loading: 'Loading...',
    createdAt: 'Created %{date}',
    avgRating: 'Average Rating',
    ratingCount: '%{avg} (%{count} ratings)',
    rateTitle: 'Rate This Outfit',
    deleteOutfit: 'Delete Outfit',
    alerts: {
      deleteTitle: 'Delete Outfit',
      deleteMsg: 'Remove this outfit?',
      deleteConfirm: 'Delete',
      cancel: 'Cancel',
    },
  },

  // Settings screen
  settings: {
    storageTitle: 'Storage',
    totalItems: 'Total Items',
    imageStorage: 'Image Storage',
    storageMb: '%{mb} MB',
    backupTitle: 'Backup & Restore',
    createBackup: 'Create Local Backup',
    backupHint: 'Saves your wardrobe database and images to your Downloads folder. Android may ask for folder access the first time.',
    connectDrive: 'Connect Google Drive',
    disconnectDrive: 'Disconnect Google Drive',
    createDriveBackup: 'Back Up to Google Drive',
    driveInfo: 'Google Drive backup requires an Android native build with Google Sign-In configured. Expo Go is not supported.',
    driveConnectedAs: 'Connected as %{email}',
    driveTitle: 'Google Drive Backup',
    driveBackupsTitle: 'Google Drive Backups',
    availableBackups: 'Available Backups',
    restore: 'Restore',
    aboutTitle: 'About',
    version: 'Version',
    framework: 'Framework',
    languageTitle: 'Language',
    themeTitle: 'Theme',
    currencyTitle: 'Currency',
    themeModes: {
      system: 'System',
      light: 'Light',
      dark: 'Dark',
    },
    currencies: {
      usd: 'USD ($)',
      eur: 'EUR (Euro)',
      gbp: 'GBP (Pound)',
      mxn: 'MXN (Peso)',
    },
    english: 'English',
    spanish: 'Español',
    alerts: {
      backupCreated: 'Backup Created',
      backupSaved: 'Backup saved to Downloads (%{size} MB).',
      backupFailed: 'Backup Failed',
      backupFailedMsg: 'Could not create backup on this device.',
      driveConnected: 'Google Drive Connected',
      driveConnectedMsg: 'Signed in as %{email}.',
      driveDisconnected: 'Google Drive Disconnected',
      driveDisconnectedMsg: 'Your Google account has been disconnected.',
      driveBackupCreated: 'Drive Backup Created',
      driveBackupSaved: 'Backup uploaded to Google Drive (%{size} MB).',
      driveBackupFailed: 'Drive Backup Failed',
      driveBackupFailedMsg: 'Could not upload backup to Google Drive.',
      restoreTitle: 'Restore Backup',
      restoreMsg: 'Restore from %{name}? This will replace all current data.',
      restoreConfirm: 'Restore',
      restored: 'Restored',
      restoredMsg: 'Backup restored. Please restart the app.',
      restoreFailed: 'Restore Failed',
      restoreFailedMsg: 'Could not restore this backup file.',
      driveRestoreFailed: 'Could not restore this Google Drive backup.',
      cancel: 'Cancel',
    },
  },

  // Components
  garmentCard: {
    unavailable: 'Unavailable',
  },
  duplicateWarning: {
    title: 'Possible Duplicate',
    subtitle: 'You may already own a similar item:',
    matchScore: '%{score}% match',
    goBack: 'Go Back',
    addAnyway: 'Add Anyway',
  },
  tagInput: {
    placeholder: 'Add tag...',
  },
  outfitPreview: {
    matchScore: 'Match score: %{score}%',
  },
  colorPicker: {
    selectColor: 'Select color',
    multiShort: 'M',
  },
  duplicateReasons: {
    similarTags: 'similar tags',
    similarColor: 'similar color',
    sameSize: 'same size',
    overallSimilarity: 'overall similarity',
  },

  colors: {
    black: 'Black', white: 'White', gray: 'Gray', navy: 'Navy', blue: 'Blue', lightBlue: 'Light Blue',
    red: 'Red', burgundy: 'Burgundy', pink: 'Pink', green: 'Green', olive: 'Olive', khaki: 'Khaki',
    brown: 'Brown', tan: 'Tan', beige: 'Beige', cream: 'Cream', yellow: 'Yellow', orange: 'Orange',
    purple: 'Purple', lavender: 'Lavender', coral: 'Coral', teal: 'Teal', gold: 'Gold', silver: 'Silver',
    multi: 'Multi',
  },

  subcategories: {
    tshirt: 'T-Shirt', blouse: 'Blouse', shirt: 'Shirt', tank_top: 'Tank Top', sweater: 'Sweater', hoodie: 'Hoodie', crop_top: 'Crop Top', polo: 'Polo',
    jeans: 'Jeans', pants: 'Pants', shorts: 'Shorts', skirt: 'Skirt', leggings: 'Leggings', sweatpants: 'Sweatpants', chinos: 'Chinos',
    mini: 'Mini', midi: 'Midi', maxi: 'Maxi', cocktail: 'Cocktail', sundress: 'Sundress', jumpsuit: 'Jumpsuit', romper: 'Romper',
    jacket: 'Jacket', coat: 'Coat', blazer: 'Blazer', cardigan: 'Cardigan', vest: 'Vest', poncho: 'Poncho', cape: 'Cape', windbreaker: 'Windbreaker', parka: 'Parka',
    sneakers: 'Sneakers', boots: 'Boots', sandals: 'Sandals', heels: 'Heels', flats: 'Flats', loafers: 'Loafers', athletic: 'Athletic',
    hat: 'Hat', scarf: 'Scarf', belt: 'Belt', bag: 'Bag', jewelry: 'Jewelry', watch: 'Watch', sunglasses: 'Sunglasses', tie: 'Tie',
    sports_bra: 'Sports Bra', workout_top: 'Workout Top', workout_shorts: 'Workout Shorts', yoga_pants: 'Yoga Pants', track_suit: 'Track Suit',
    bra: 'Bra', briefs: 'Briefs', boxers: 'Boxers', bodysuit: 'Bodysuit', shapewear: 'Shapewear', socks: 'Socks', tights: 'Tights', thermal: 'Thermal',
    pajama_set: 'Pajama Set', pajama_top: 'Pajama Top', pajama_bottoms: 'Pajama Bottoms', nightgown: 'Nightgown', robe: 'Robe', lounge_set: 'Lounge Set',
  },

  // Categories
  categories: {
    tops: 'Tops',
    bottoms: 'Bottoms',
    dresses: 'Dresses',
    midlayer: 'Mid-Layer',
    outerwear: 'Outerwear',
    shoes: 'Shoes',
    accessories: 'Accessories',
    activewear: 'Activewear',
    underwear: 'Underwear',
    loungewear: 'Loungewear/Pajamas',
  },
};

export default en;
export type TranslationKeys = typeof en;



