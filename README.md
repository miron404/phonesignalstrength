# 📶 Smartphone Signal Strength Viewer

- **Package Name:** `com.cellsignalstrength`
- **Minimum Android Version:** Android 8 (Oreo)
- **Supports Dual SIM Devices**
- **Completely Offline & Lightweight**

💾   Download the Android Phone Signal Strength APK file: [https://github.com/Eb43/phonesignalstrength/releases](https://github.com/Eb43/phonesignalstrength/releases)


---

## 👀 Screenshots

<div style="">
  <img alt="phone network signal strength viewer on Android" src="https://raw.githubusercontent.com/Eb43/phonesignalstrength/main/Screenshot_2025-05-28-19-04-28-077_lockscreen.jpg" style="display: inline-block; margin-left:30px; width: 300px; object-fit: none; object-position: 1% 1%"/>

<img alt="phone network signal strength viewer on Android" src="https://raw.githubusercontent.com/Eb43/phonesignalstrength/main/Screenshot_20250528-183717.jpg" style="width:300px; display: inline-block; margin-left:30px;"/>
</div>

<div style="">
  <img alt="phone network signal strength viewer on Android" src="https://raw.githubusercontent.com/Eb43/phonesignalstrength/main/Screenshot_20250528-183650.jpg" style="width:900px; display: inline-block; margin-left:30px;"/>
  </div>
  
  <div style="">
<img alt="phone network signal strength viewer on Android" src="https://raw.githubusercontent.com/Eb43/phonesignalstrength/main/Screenshot_20250528-183704.jpg" style="width:300px; display: inline-block; margin-left:30px;"/>
</div>

## 📝 Overview

**Cell Signal Strength** is a simple yet powerful Android app designed to display real-time cellular signal strength in **dBm (decibel-milliwatts)** — the most accurate and reliable metric for evaluating mobile reception quality. The app visually and numerically represents signal strength and provides user-selectable SIM slot monitoring and status bar customization for maximum usability.

The user interface design allows for any non tech-profound person to understand the displayed value through visual graphic indicator of the signal strength level.

---

## 📱 Features

### 🔢 Accurate dBm Readings

* Real-time signal strength is displayed in the **standard dBm format**, ranging typically from -40 dBm (excellent) to -120 dBm (no signal).
* Updates dynamically as signal conditions change.

### 📶 Visual Signal Strength Indicator

* A **color gradient signal bar** with a movable triangle marker shows signal strength graphically.
* Helps users quickly gauge signal quality at a glance.

### 📊 Dual SIM Support

* Easily toggle between **SIM 1** and **SIM 2** using a simple radio button interface.
* Displays separate signal readings for each SIM slot.

### 🎨 Status Bar Icon Color Customization

* Choose between **black or white text color** for the status bar icon.
* Ensures best contrast based on your device’s theme or status bar background.

### 🔀 Auto Start on Boot (Optional)

* Enable automatic background launch after device reboot.
* Ensures signal monitoring remains active without manual intervention.

### ❌ Easy Exit Button

* Tap the kill button (`☠️ ❌`) to completely stop the app and background service.
* Useful for battery-conscious users or temporary use.

---

## 🧯 UI Layout

| UI Element                         | Description                                                      |
| ---------------------------------- | ---------------------------------------------------------------- |
| **App Icon**                       | Displayed at the top for branding.                               |
| **Main Label**                     | "Cell Phone Signal Strength" in large font.                      |
| **Current Signal TextView**        | Shows real-time dBm and network type (e.g., `📶 -85 dBm 4G`). |
| **Gradient Bar + Triangle Marker** | Provides a visual cue of signal strength.                         |
| **dBm Value Label**                | Displays the floating market with actual dBm level just below.    |
| **SIM Selector**                   | Dual SIM support. Switch between SIM 1 and SIM 2.      |
| **Text Color Selector**            | Toggle between black and white text for the notification bar.    |
| **Auto Start Checkbox**            | Enables/disables auto-start on smartphone boot.                  |
| **Exit Button**                    | Stops the service and closes the app.                            |

---

## ⚙️ How It Works

* The app uses the `TelephonyManager` API to access **cell signal strength** data, updated every second.
* It runs a background `Service` (`SignalStrengthService.java`) which collects and pushes updates to the UI.
* On supported devices, it fetches signal strength separately for SIM 1 and SIM 2.
* The triangle marker on the gradient bar adjusts position according to the dBm value.
* Users can configure the icon color in the status bar to improve visibility.

---

## 🔧 Daily Use Cases

* 📡 **Troubleshooting Connectivity:** Quickly determine weak or dead zones in your house, office, or neighborhood.
* 🌍 **Traveling:** Monitor how well your phone maintains a connection while on the move.
* 📱 **Dual SIM Management:** Compare and switch between SIM providers for better reception.
* 🔋 **Battery Optimization:** Exit the app when not needed or let it run in the background for ongoing monitoring.

---

## 🛠️ Installation

### Requirements

* Android Studio (for building)
* Android 8.0 (API 26) or above
* Dual SIM phone (for dual SIM features)

### Steps

1. Clone the repository:

   ```bash
   git clone https://github.com/yourusername/cell-signal-strength.git
   ```
2. Open in **Android Studio**.
3. Build and run on a real Android device (not emulator, due to telephony API restrictions).


---

### 🇷🇺 Обзор (Russian)

**Cell Signal Strength** — это простое, но мощное Android-приложение, предназначенное для отображения уровня сигнала сотовой сети в режиме реального времени в **дБм (децибел-милливаттах)** — самой точной и надежной метрике для оценки качества мобильной связи. Приложение отображает уровень сигнала как численно, так и визуально, а также позволяет пользователю выбирать SIM-карту и настраивать отображение значка в строке состояния для максимального удобства.

Интерфейс приложения разработан таким образом, чтобы любой пользователь, даже не обладающий техническими знаниями, мог легко понять отображаемое значение благодаря графическому индикатору уровня сигнала.

---

### 🇵🇹 Visão Geral (Portuguese)

**Cell Signal Strength** é um aplicativo Android simples, mas poderoso, projetado para exibir a intensidade do sinal celular em tempo real em **dBm (decibéis-miliwatts)** — a métrica mais precisa e confiável para avaliar a qualidade do sinal móvel. O aplicativo representa o sinal tanto visualmente quanto numericamente, além de permitir ao usuário selecionar o slot do SIM e personalizar o ícone da barra de status para maior usabilidade.

A interface do usuário foi projetada para que qualquer pessoa, mesmo sem conhecimento técnico, possa entender facilmente o valor exibido através do indicador gráfico de intensidade do sinal.

---

### 🇩🇪 Übersicht (German)

**Cell Signal Strength** ist eine einfache, aber leistungsstarke Android-App, die die aktuelle Signalstärke des Mobilfunknetzes in **dBm (Dezibel-Milliwatt)** anzeigt — der genauesten und zuverlässigsten Maßeinheit zur Bewertung der Netzqualität. Die App stellt die Signalstärke sowohl visuell als auch numerisch dar und ermöglicht die Auswahl des SIM-Slots sowie die Anpassung des Statusleistensymbols für maximale Benutzerfreundlichkeit.

Das benutzerfreundliche Design der Oberfläche ermöglicht es auch technisch weniger versierten Personen, die angezeigten Werte dank der grafischen Signalstärkeanzeige leicht zu verstehen.

---

### 🇪🇸 Descripción General (Spanish)

**Cell Signal Strength** es una aplicación Android simple pero potente, diseñada para mostrar en tiempo real la intensidad de la señal celular en **dBm (decibelios-miliwatts)**, la métrica más precisa y confiable para evaluar la calidad de la recepción móvil. La app representa la señal de manera visual y numérica, y permite al usuario seleccionar la ranura SIM y personalizar el icono en la barra de estado para una mayor usabilidad.

La interfaz de usuario está diseñada para que cualquier persona, incluso sin conocimientos técnicos, pueda comprender fácilmente el valor mostrado mediante el indicador gráfico del nivel de señal.

---

### 🇨🇳 概述 (Chinese - Simplified)

**Cell Signal Strength** 是一款简单但功能强大的 Android 应用程序，用于实时显示蜂窝信号强度，单位为 **dBm（分贝毫瓦）**——这是评估移动信号质量最准确、最可靠的指标。该应用程序以图形和数值的形式展示信号强度，并提供用户可选择的 SIM 卡槽监控和状态栏图标自定义功能，以实现最大的可用性。

用户界面设计直观，即使是非技术人员也能通过图形化的信号强度指示器轻松理解显示的数值。
