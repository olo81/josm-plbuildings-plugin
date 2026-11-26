# PLBuildings plugin
[Read in English](README.en.md).

## Opis
PLBuildings to wtyczka do [JOSMa](https://josm.openstreetmap.de/), która umożliwia użytkownikom łatwe importowanie budyków z publicznych polskich zbiorów danych w celu dodania ich do OpenStreetMap.

Wtyczka komunikuje się z [serwerem PLBuildings](https://github.com/praszuk/josm-plbuildings-server) w celu pozyskiwania danych.

## Funkcjonalności
- Prosty import budynku w miejscu kursora myszy.
- Zastępowanie geometrii, jeśli stary budynek jest zaznaczony.
- Aktualizacja tagów z możliwością pominięcia konfliktów _częstych tagów_ np. takich jak z `building=yes` na `building=house` bez wyświetlania okna dialogowego z konfliktem tagów.
- Łączenie budynku z istniejącym budynkiem. Sąsiadujące węzły będą współdzielone między budynkami – zapobiega to nakładającym/duplikującym się węzłom.
- Zapobieganie zduplikowanych importów.
- Proste statystyki (zliczalnie zaimportowanych i podmienionych budynków).
- Wielojęzyczność z użyciem i18n (polski i angielski).
- Wstępne sprawdzanie budynków przed zastąpieniem:
    - Szukanie wartości "survey".
    - Zapobieganie zubożeniu tagów (np. `detached` -/> `house`)
    - Unikanie błędów związanych z `building:levels` w zależności od tego, czy zaznaczony budynek ma tag `roof:levels`.
- Sprawdzanie zaimportowanych budynków w celu powiadomienia o nietypowych tagach.
- Sprawdzanie zaimportowanych budynków w celu powiadomienia o tagach z prefiksami cyklu życia.
- Obsługa tagu `construction=*`.
- Statusy (aktualny stan akcji importu).
- Tryby importu:
    - Pełny – domyślny (pełny import nowego budynku lub pełna zamiana)
    - Geometria – aktualizacja tylko geometrii dla zaznaczonego budynku
    - Tagi – aktualizacja tylko tagów dla zaznaczonego budynku
- Ustawienia:
    - Konfiguracje wielu serwerów
    - Wiele źródeł danych, w tym złączone źródła danych (np. 1 dla geometrii, 2 dla tagów) – zawiera to również mechanizm, który obsługuje braki w wybranym źródle.
    - Powiadomienia
    - Nietypowe tagi
    - Auto usuwanie źródła
    - ...więcej ustawień można znaleźć w ustawieniach zaawansowanych JOSMa, szukając po kluczu `plbuildings.`
- Boczny panel GUI (tzw. Toggle Dialog), który zawiera:
    - Aktualny status
    - Tryb importu
    - Źródło danych
    - Ostatnie tagi (wraz ze wskaźnikiem nietypowych tagów)
- Automatyczne dodawanie tagów `source:building` i `source:geometry`.
- Automatyczne usuwanie wybranych wartości z klucza `source` (np. `geoportal.gov.pl`).
- Okno konfliktów tagów z opcją „Zapamiętaj” – konflikt każdej pary tagów (np. `building=manufacture` vs `building=outbuilding`, różne wartości `building:levels`) można rozstrzygnąć raz, a wybór (wartość z OSM lub z importu) jest zapamiętywany dla tej konkretnej pary tagów do zamknięcia JOSMa.
- Ostrzeżenie o częstych konfliktach – gdy zapamiętana para tagów wejdzie w konflikt po raz 10., wtyczka pokazuje jednorazowe ostrzeżenie o możliwym imporcie złej jakości z linkiem do Discorda społeczności OSM Polska (https://discord.gg/96y4wSxG5).
- Import wielu zaznaczonych części budynku jako jednego budynku – adresy części są wyciągane jako osobne węzły adresowe, a usługi (np. `shop=*`, `amenity=*`) jako osobne punkty; cała operacja jest jednym krokiem cofania.
- Zachowanie obiektów „przyklejonych” do budynku – drzwi (`entrance=*`), ogrodzenia (`barrier=*`), kamery i linie energetyczne przyczepione do obrysu są przenoszone na najbliższe punkty nowego obrysu.
- Przycinanie ogrodzeń przecinających nowy obrys budynku, łączenie zdublowanych węzłów i usuwanie ogrodzeń leżących na ścianach budynku.
- Ochrona obszarów `landuse` – obszary przyklejone do budynku nie są ruszane ani łączone przy imporcie.
- Bezpieczne wycofywanie zmian – w razie błędu importu geometria budynku jest przywracana, a w warstwie nie zostają referencje do usuniętych węzłów.

## Konflikty tagów z opcją zapamiętania
Przy zastępowaniu budynku danymi z importu, jeśli tagi różnią się od tych w OSM (np. OSM: `building=manufacture`, import: `building=outbuilding`; inne wartości `building:levels`; nietypowe tagi przy `building=yes` z importu), pojawia się jedno okno z listą wszystkich konfliktów. Przy każdym konflikcie jest pole „Zapamiętaj” – zaznaczenie go zapamiętuje wybraną wartość (z OSM lub z importu) dla tej konkretnej pary tagów do zamknięcia JOSMa. Kolejne konflikty tej samej pary są wtedy rozstrzygane automatycznie, bez okna.

Gdy zapamiętana para wejdzie w konflikt po raz 10. i wybrana wartość ulegnie zmianie, wtyczka pokazuje jednorazowy komunikat, że konflikt występuje wiele razy i że w okolicy mógł zostać wykonany import złej jakości – z klikalnym linkiem do Discorda społeczności OSM Polska: https://discord.gg/96y4wSxG5.

## Import wielu zaznaczonych części budynku
Zaznaczenie kilku części jednego bloku (np. trzech segmentów z osobnymi adresami) i uruchomienie importu zastępuje je jednym budynkiem z importu. Adresy (`addr:*`) części są wyciągane jako osobne węzły adresowe umieszczone w środkach części. Usługi części (np. `shop=*`, `amenity=*`, `office=*`) są wyciągane do osobnych punktów – po jednym punkcie na usługę; pojedyncza usługa dzieli węzeł z adresem części (sklep z adresem to jeden punkt), a przy kilku usługach każda dostaje własny węzeł. Drzwi i inne obiekty przyklejone do części zostają przeniesione na nowy obrys. Cała operacja jest jednym krokiem cofania (Ctrl+Z). Import zostanie przerwany, jeśli któraś z części należy do relacji.

## Obiekty przyklejone do budynku i ogrodzenia
Przy zamianie geometrii obiekty przyczepione do obrysu w punktach – drzwi (`entrance=*`), ogrodzenia (`barrier=*`), kamery, linie energetyczne itp. – zostają przeniesione na najbliższe punkty nowego obrysu i pozostają do niego przyczepione. Ogrodzenia przecinające nowy obrys są przycinane w punktach przecięcia (z ponownym użyciem istniejących węzłów), węzły leżące w tym samym miejscu są łączone, a ogrodzenia leżące wzdłuż ścian budynku są usuwane. Obszary `landuse` przyklejone do budynku pozostają nienaruszone.

## Jak z niej korzystać
1. Zainstaluj wtyczkę w JOSMie (tak jak każdą inną – przez ustawienia JOSMa).
2. Otwórz boczny panel (Toggle Dialog): Po lewej stronie powinna być widoczna ikonka z budynkami (logiem tej wtyczki) – klknij na nią. Po prawej stronie powinien otworzyć się boczny panel wtyczki taki jak na GIFie poniżej.
3. Wybierz tryb importu i źródło danych – zostanie to zapamiętane, ale rekomenduję zawsze mieć otwarty boczny panel dla łatwiejszej zmiany trybu/źródła i aby mieć na uwadze ostatni import.
4. Nakieruj kursor myszy na widoczny budynek i naciśnij `CTRL + SHIFT + 1` – spowoduje to import budynku. Jeśli przed wciśnięciem skrótu został zaznaczony inny budynek, zostanie użyta funkcja zamiany geometrii z pluginu [utilsplugin2](https://wiki.openstreetmap.org/wiki/JOSM/Plugins/utilsplugin2) w celu aktualizacji starego kształtu. Przy różnicach w tagach wtyczka wyświetli własne okno konfliktów z możliwością zapamiętania wyboru (patrz wyżej).

Notka: Jeśli skrót nie działa (może się tak zdarzyć) lub po prostu chcesz go zmienić na inny, wejdź w ustawienia skrótów w JOSMie i wpisz: `plbuildings`, następnie zmień skrót dla `Pobierz budynek`.

**UWAGA! Każdy import powinien zostać zweryfikowany!** Nie ma danych idealnych, stąd mogą się czasem pojawić drobne błędy związane np. z typem budynku lub liczbą pięter, ale również np. z budynkiem pełniącym inną [funkcję użytkową](https://wiki.openstreetmap.org/wiki/Pl:Key:building:use).

Prosty import budynku:
![Prosty import budynku](media/simple_import.gif)

Import z zamianą starej geometrii budynku:
![Import z zamianą starej geometrii budynku](media/import_with_replace.gif)

W celu wyświetlenia statystyk otwórz: `Menu->Dane->Statystyki importowania budynków`

Przykładowe statystyki:

![Statystyki importowania budynków](media/import_stats2.jpg)

Boczny panel ze statusem zmieniającym się podczas importu:

![Boczny panel podczas importu](media/toggledialog2.gif)

Ustawienia wtyczki `Menu->Dane->PlBuildings: Ustawienia`:

![Ustawienia wtyczki: źródła danych](media/settings_data_sources.png)
![Ustawienia wtyczki: powiadomienia](media/settings_notifications.png)
![Ustawienia wtyczki: nietypowe tagi](media/settings_uncommon_tags.png)
![Ustawienia wtyczki: auto usuwanie źródła](media/settings_autoremove_source.png)

## Licencja
[GPLv3](LICENSE)