# Eikonix

A modern, Jetpack Compose-based Android application for reading comic books in `.cbz` and `.zip` formats. Inspired by the bold aesthetics of Marvel and DC, Eikonix offers a seamless and immersive reading experience.

## Features

- **📂 Library Management**: Easily add folders from your device to automatically scan for and organize your comic collection.
- **📖 Flexible Reading Modes**: Choose between **Horizontal Sliding** and **Vertical Swiping** to suit your reading preference.
- **🔍 Fast Search**: Quickly find any comic in your library with the integrated search functionality.
- **🔎 Zoom & Pan**: High-quality image rendering with support for pinch-to-zoom, double-tap to zoom, and smooth panning.
- **🎨 Comic-Inspired Theme**: A striking dark theme with a palette inspired by classic comic book colors.
- **🔢 Natural Sorting**: Smart sorting logic ensures that pages and titles are ordered correctly (e.g., "Page 2" comes before "Page 10").
- **🚀 Performance Optimized**: Efficient bitmap decoding and caching for a lag-free experience, even with large files.

## Tech Stack

- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose) for a modern, declarative UI.
- **Navigation**: [Compose Navigation](https://developer.android.com/jetpack/compose/navigation).
- **Architecture**: MVVM (Model-View-ViewModel) with `StateFlow` and `viewModelScope`.
- **Concurrency**: Kotlin Coroutines and Flow for asynchronous operations.
- **File Handling**: Android Storage Access Framework (SAF) and `ZipInputStream` for reading archive files.

## Project Structure

- `MainActivity.kt`: Contains the navigation logic and main UI screens (Library and Reader).
- `ComicViewModel.kt`: Manages the state of the current comic, including page loading and bitmap retrieval.
- `ComicUtils.kt`: Utility functions for parsing CBZ files, extracting thumbnails, and handling natural order sorting.
- `ComicModel.kt`: Defines the data structures for `Comic` and `ComicPage`.
- `ui.theme/`: Custom Material 3 theme implementation with comic-inspired colors and typography.

## Getting Started

1. Clone the repository.
2. Open the project in **Android Studio Hedgehog** or newer.
3. Build and run on an Android device or emulator (API 24+).
4. Use the "Add Folder" button to select a directory containing your `.cbz` or `.zip` comic files.

## Screenshots

*(Add screenshots here)*

---

Developed with ❤️ for comic enthusiasts.
