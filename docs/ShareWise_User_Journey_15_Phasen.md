# ShareWise – Erweiterte User Journey (15 Phasen)

## Kontext und Einordnung

Die User Journey der Vorlesung beschreibt anhand der Gruppe **„Mallorca 2027“** zehn wesentliche Schritte – von der Registrierung über das Erfassen von Ausgaben und Rückzahlungen bis zur Archivierung. Diese erweiterte Fassung beschreibt **15 Phasen** mit regulären Abläufen, Handlungsalternativen, Sonderfällen und fachlichen Bedingungen.

> **Status der Anforderungen:** Die hier beschriebenen zusätzlichen Funktionen und Geschäftsregeln sind **Vorschläge für das ShareWise-Projekt**, nicht sämtlich verbindliche Festlegungen der Vorlesung. Sie sollten im Team fachlich abgestimmt werden.

---

## Phase 1 – User Registration & Authentication

*Original Step 1: A user registers*

### 1.1 ShareWise öffnen

Ein Benutzer öffnet ShareWise:

- Er ist bereits registriert → Er kann sich anmelden.
- Er hat noch keinen Account → Er kann sich registrieren.
- Er hat sein Passwort vergessen → Er kann es zurücksetzen.
- Er möchte die Anwendung verlassen → Es erfolgt keine Aktion.

### 1.2 Registrierung

- Der Benutzer gibt Namen, E-Mail-Adresse und Passwort ein.
- Die E-Mail-Adresse wird validiert.
- Existiert die E-Mail-Adresse bereits, wird die Registrierung abgelehnt.
- Sind Angaben ungültig, wird eine Fehlermeldung angezeigt.
- Optional bestätigt der Benutzer seine E-Mail-Adresse.

### 1.3 Nach der Anmeldung

- Der Benutzer gelangt zu seinem Dashboard.
- Er sieht seine bestehenden Gruppen.
- Er kann eine neue Gruppe erstellen.
- Er kann eine Einladung zu einer Gruppe annehmen.

### 1.4 Weitere Account-Aktionen

- Profilinformationen ändern.
- Passwort ändern.
- Sich abmelden.
- Account löschen lassen; vorhandene finanzielle Aufzeichnungen müssen dabei angemessen berücksichtigt werden.

---

## Phase 2 – Group Creation & Management

*Original Step 2: The user creates the group “Mallorca 2027”*

### 2.1 Max erstellt die Gruppe

1. Max wählt **Create Group**.
2. Er gibt den Namen **„Mallorca 2027“** ein.
3. Er ergänzt optional eine Beschreibung.
4. Er legt die Gruppenwährung fest, z. B. **EUR**.
5. Das System erstellt die Gruppe.
6. Max wird automatisch **Group Admin** und **Group Member**.

### 2.2 Alternative Abläufe

- Name fehlt → Die Gruppe kann nicht erstellt werden.
- Ungültige Gruppendaten → Fehlermeldung.
- Benutzer bricht ab → Keine Gruppe wird erstellt.
- Benutzer erstellt versehentlich mehrere Gruppen → Sie bleiben zunächst eigenständige Gruppen.

### 2.3 Gruppe verwalten

Ein berechtigter Benutzer kann:

- Gruppenname und Beschreibung ändern.
- Mitglieder einladen.
- Mitglieder entfernen oder deaktivieren.
- Admin-Rechte vergeben oder entziehen.
- Die Gruppe verlassen, ggf. nach Übertragung der Admin-Rolle.
- Eine Gruppe ohne finanzielle Einträge löschen (sofern diese Funktion vorgesehen ist).
- Die Gruppe später schließen oder archivieren.

---

## Phase 3 – Inviting Friends

*Original Step 3: Friends are invited*

### 3.1 Max lädt Freunde ein

Max möchte **Anna, Lisa und Tom** einladen. Dafür stehen möglicherweise folgende Wege bereit:

- Einladung per E-Mail-Adresse.
- Einladung per Link.
- Auswahl bereits registrierter ShareWise-Benutzer.

### 3.2 Einladung prüfen

- Person ist bereits Gruppenmitglied → Keine zweite Mitgliedschaft anlegen.
- Person ist bereits eingeladen → Bestehende Einladung anzeigen oder erneut versenden.
- Person besitzt bereits einen Account → Einladung diesem Account zuordnen.
- Person besitzt keinen Account → Einladung mit Registrierungsmöglichkeit anbieten.
- E-Mail-Adresse ist ungültig → Fehlermeldung.
- Gruppe nimmt keine Mitglieder mehr auf → Einladung ablehnen.

### 3.3 Einladungen verwalten

Ein berechtigter Benutzer kann:

- Ausstehende Einladungen ansehen.
- Eine Einladung erneut versenden.
- Eine Einladung zurückziehen.
- Einen Einladungslink deaktivieren.

---

## Phase 4 – Joining a Group

*Original Step 4: The invited users join the group*

### 4.1 Anna erhält eine Einladung

Anna kann:

- Die Einladung annehmen.
- Die Einladung ablehnen.
- Die Einladung ignorieren.

### 4.2 Einladung annehmen

1. Anna meldet sich an oder registriert sich, falls sie noch keinen Account hat.
2. ShareWise prüft die Gültigkeit der Einladung.
3. Anna wird **Group Member**.
4. Sie erhält Zugriff auf die für sie freigegebenen Gruppeninformationen.

### 4.3 Alternative Abläufe

- Einladung abgelaufen → Neue Einladung erforderlich.
- Einladung zurückgezogen → Beitritt nicht möglich.
- Benutzer bereits Mitglied → Kein erneuter Beitritt.
- Einladung abgelehnt → Kein Beitritt.
- Beitrittsversuch ohne gültige Einladung → Zugriff verweigert.

### 4.4 Aktionen nach dem Beitritt

Anna kann:

- Gruppenausgaben ansehen.
- Eigene Ausgaben erfassen.
- Als Teilnehmerin einer Ausgabe berücksichtigt werden.
- Schulden und Guthaben ansehen.
- Rückzahlungen erfassen.
- Die Gruppe unter den definierten Bedingungen verlassen.

---

## Phase 5 – Recording Shared Expenses

*Original Step 5: Members record shared expenses*

### 5.1 Max erfasst eine Ausgabe

Max bezahlt ein Abendessen für **120 €** und erfasst:

- Beschreibung: **Dinner**.
- Betrag: **120 €**.
- Zahler: **Max**.
- Teilnehmer: **Max, Anna, Lisa und Tom**.
- Datum der Ausgabe.
- Optional: Kategorie, Kommentar oder Beleg.

### 5.2 Ausgaben aufteilen

Das System unterstützt als mögliche Varianten:

- **Equal split:** Alle beteiligten Personen zahlen denselben Anteil.
- **Custom split:** Die Beteiligten erhalten frei festgelegte Beträge.
- **Percentage split:** Die Beteiligten tragen prozentuale Anteile.

#### Beispiel: Gleiche Aufteilung von 120 €

| Teilnehmer | Anteil |
|---|---:|
| Max | 30 € |
| Anna | 30 € |
| Lisa | 30 € |
| Tom | 30 € |
| **Summe** | **120 €** |

#### Beispiel: Individuelle Beträge

| Teilnehmer | Anteil |
|---|---:|
| Max | 20 € |
| Anna | 40 € |
| Lisa | 50 € |
| Tom | 10 € |
| **Summe** | **120 €** |

#### Beispiel: Prozentuale Verteilung

| Teilnehmer | Anteil | Betrag |
|---|---:|---:|
| Max | 10 % | 12 € |
| Anna | 20 % | 24 € |
| Lisa | 30 % | 36 € |
| Tom | 40 % | 48 € |
| **Summe** | **100 %** | **120 €** |

### 5.3 Weitere mögliche Abläufe

- Nur ein Teil der Gruppenmitglieder nimmt an einer Ausgabe teil.
- Der Zahler ist nicht selbst Teilnehmer der Ausgabe.
- Zwei Mitglieder haben gemeinsam bezahlt; dies erfordert entweder mehrere Zahler oder getrennte Ausgaben.
- Eine Ausgabe wird nach individuellen Beträgen aufgeteilt.
- Ein Mitglied legt für andere Mitglieder Geld aus.
- Der Benutzer bricht die Erfassung ab.
- Mehrere Mitglieder erfassen gleichzeitig verschiedene Ausgaben.

### 5.4 Validierung

- Der Betrag muss positiv sein.
- Der Zahler muss Gruppenmitglied sein.
- Alle Teilnehmer müssen Gruppenmitglieder sein.
- Es muss mindestens einen Teilnehmer geben.
- Die Summe aller Kostenanteile muss genau dem Gesamtbetrag entsprechen.
- In geschlossenen oder archivierten Gruppen dürfen keine neuen Ausgaben erfasst werden.

---

## Phase 6 – Editing & Deleting Expenses

*Original Step 6: Expenses are corrected or removed when necessary*

### 6.1 Fehler erkennen

Anna bemerkt, dass Max versehentlich **120 €** für das Abendessen erfasst hat, obwohl es nur **100 €** gekostet hat.

### 6.2 Handlungsalternativen für Anna

- Ausgabe ansehen.
- Fehler an Max melden.
- Die Ausgabe selbst bearbeiten, sofern sie dazu berechtigt ist.

### 6.3 Mögliche Änderungen durch berechtigte Mitglieder

- Betrag korrigieren.
- Beschreibung ändern.
- Zahler ändern.
- Teilnehmer hinzufügen oder entfernen.
- Aufteilungsregel ändern.
- Datum korrigieren.
- Ausgabe stornieren.

### 6.4 Auswirkungen einer Änderung

1. Die neuen Angaben werden validiert.
2. Die Kostenanteile werden entsprechend angepasst.
3. Schulden und Guthaben werden neu berechnet.
4. Die Änderung wird in der Historie dokumentiert.
5. Eine stornierte Ausgabe beeinflusst die aktuelle Bilanz nicht mehr.

### 6.5 Sonderfälle

- Keine Berechtigung → Änderung wird abgelehnt.
- Gleichzeitige Bearbeitung durch ein anderes Mitglied → Versionskonflikt behandeln.
- Ausgabe bereits Teil einer bestätigten Abrechnung → Nur definierter Korrekturprozess erlaubt.
- Gruppe geschlossen → Keine direkte Änderung.
- Gruppe archiviert → Nur lesender Zugriff.

---

## Phase 7 – Reviewing Balances & Debts

*Original Step 7: Members review their current balances*

### 7.1 Anna öffnet die Gruppenübersicht

Sie kann:

- Ihren aktuellen Saldo sehen.
- Sehen, wem sie Geld schuldet.
- Sehen, wer ihr Geld schuldet.
- Alle Gruppenausgaben ansehen.
- Ausgaben nach Datum oder Teilnehmer filtern.
- Einzelne Kostenanteile nachvollziehen.

### 7.2 Beispiel für die Bilanz

Max bezahlt **120 €** für vier Personen. Jede Person übernimmt **30 €**.

| Mitglied | Bilanz |
|---|---:|
| Max | +90 € |
| Anna | −30 € |
| Lisa | −30 € |
| Tom | −30 € |
| **Gesamt** | **0 €** |

- **Positiver Saldo:** Mitglied soll netto Geld zurückbekommen.
- **Negativer Saldo:** Mitglied schuldet netto Geld.

### 7.3 Weitere Aktionen

- Direkte Forderungen zwischen einzelnen Mitgliedern ansehen.
- Vereinfachte Rückzahlungsvorschläge anzeigen.
- Ausgabenhistorie prüfen.
- Fehlerhafte Ausgabe beanstanden.
- Rückzahlung vorbereiten.

### 7.4 Fehler- und Sonderfälle

- Keine Ausgaben vorhanden → Alle Salden sind 0 €.
- Ausgabe korrigiert → Salden werden neu berechnet.
- Rückzahlung erfasst und entsprechend verbucht → Salden werden angepasst.
- Mitglied ausgetreten → Historische Verbindlichkeiten bleiben nachvollziehbar.

---

## Phase 8 – Settlement Payments

*Original Step 8: Members record settlement payments*

### 8.1 Anna möchte ihre Schulden begleichen

Anna schuldet Max **30 €** und kann:

- Die gesamten 30 € begleichen.
- Eine Teilzahlung leisten.
- Die Zahlung verschieben.

### 8.2 Anna bezahlt Max

Die Zahlung findet **außerhalb von ShareWise** statt und wird anschließend dokumentiert:

- Schuldner: **Anna**.
- Empfänger: **Max**.
- Betrag: **30 €**.
- Optional: Zahlungsdatum und Kommentar.

### 8.3 Bestätigung der Rückzahlung (vorgeschlagener Ablauf)

1. Anna erfasst die Rückzahlung.
2. Max erhält eine Mitteilung.
3. Max bestätigt den Zahlungseingang.
4. Die Rückzahlung erhält den Status **bestätigt**.
5. Die Bilanzen werden aktualisiert.

### 8.4 Alternative Abläufe und Sonderfälle

- Anna zahlt nur 10 € → Restschuld 20 €.
- Falschen Betrag eingetragen → Korrektur nötig.
- Dieselbe Zahlung zweimal erfasst → Duplikat erkennen oder klären.
- Max hat nicht bestätigt → Zahlung bleibt ausstehend.
- Max lehnt den Zahlungseingang ab → Keine Auswirkung auf die bestätigte Bilanz.
- Anna storniert eine noch nicht bestätigte Zahlung.
- Bestätigte Zahlung fehlerhaft → Korrektur durch Stornierung oder Gegenbuchung mit Historie.
- Rückzahlung erfolgt persönlich → Zahlung kann ebenfalls als erledigt dokumentiert werden.

> **Systemgrenze:** In diesem vorgeschlagenen Funktionsumfang dokumentiert ShareWise Rückzahlungen, führt aber selbst keine Banküberweisungen aus.

---

## Phase 9 – Closing the Group for Settlement

*Original Step 9: An authorized member closes the group for settlement*

### 9.1 Die Reise ist beendet

Max möchte die Gruppe für die Endabrechnung schließen.

### 9.2 Max wählt „Close Group for Settlement“

Das System prüft:

- Ist Max berechtigt?
- Ist die Gruppe noch aktiv?
- Gibt es unvollständige oder strittige Ausgaben?
- Können die finalen Salden berechnet werden?

### 9.3 Auswirkungen des Schließens

- Neue Ausgaben sind nicht mehr erlaubt.
- Vorhandene Ausgaben werden für die Endabrechnung festgehalten.
- Finale Salden werden berechnet.
- Mitglieder können noch offene Schulden begleichen.
- Die Gruppe erhält den Status `SETTLING`.

### 9.4 Alternative Abläufe

- Benutzer nicht berechtigt → Schließen abgelehnt.
- Gruppe bereits geschlossen → Kein zweiter Schließvorgang.
- Es gibt offene Schulden → Schließen ist dennoch möglich.
- Vergessene Ausgabe muss ergänzt werden → Gruppe ggf. wieder öffnen.
- Ein Mitglied meldet einen Fehler → Korrekturprozess oder Wiedereröffnung.
- Admin möchte das Schließen rückgängig machen → **Reopen Group**, sofern vorgesehen.

> **Empfohlene Geschäftsregel:** Zum Schließen müssen nicht alle Salden 0 € betragen. Das Schließen beendet die Ausgabenerfassung und eröffnet die finale Begleichung offener Schulden.

---

## Phase 10 – Archiving the Group

*Original Step 10: The group is archived*

### 10.1 Vollständige Abrechnung

- Alle Rückzahlungen sind abgeschlossen.
- Alle erforderlichen Zahlungsbestätigungen liegen vor.
- Die finalen Salden aller Gruppenmitglieder betragen **0 €**.

### 10.2 Gruppe archivieren

1. Ein berechtigtes Mitglied wählt die Archivierungsfunktion.
2. ShareWise prüft die Bedingungen für die Archivierung.
3. Der Gruppenstatus wechselt zu `ARCHIVED`.
4. Die Gruppenhistorie bleibt erhalten.

### 10.3 Weiterhin erlaubte Aktionen

- Historische Ausgaben ansehen.
- Zahlungshistorie prüfen.
- Finale Kostenverteilung einsehen.
- Die Gruppe in der Archivübersicht finden.

### 10.4 Nicht mehr erlaubte Aktionen

- Neue Ausgaben erfassen.
- Vorhandene Ausgaben direkt bearbeiten.
- Neue Mitglieder einladen.
- Weitere Zahlungen hinzufügen.
- Gruppenberechtigungen verändern.

### 10.5 Sonderfälle

- Offene Schulden → Archivierung nicht möglich.
- Zahlungsbestätigung ausstehend → Archivierung wird verhindert.
- Wiederherstellung erwünscht → Nur über einen gesondert definierten Prozess.
- Mitglied löscht Account → Historische Finanzdaten entsprechend den festgelegten Aufbewahrungsregeln behandeln.

---

## Phase 11 – Leaving a Group

*Zusätzlicher Benutzerablauf, unabhängig vom ursprünglichen zehnstufigen Hauptprozess.*

Ein Mitglied möchte die Gruppe verlassen:

- Es hat keine offenen finanziellen Verpflichtungen → Regulärer Austritt möglich.
- Es hat noch offene Schulden → Diese bleiben auch nach dem Austritt bestehen.
- Andere Personen schulden ihm Geld → Die Forderungen bleiben erhalten.
- Es ist der einzige Admin → Admin-Rechte müssen vor dem Austritt übertragen werden.
- Nach dem Austritt kann es keine neuen Gruppenausgaben mehr erfassen.

> **Fachlicher Hinweis:** Das Verlassen einer Gruppe darf nicht dazu führen, dass bestehende Forderungen oder Schulden verloren gehen.

---

## Phase 12 – Removing a Member

*Zusätzlicher Benutzerablauf, unabhängig vom ursprünglichen zehnstufigen Hauptprozess.*

Ein Group Admin möchte ein anderes Mitglied entfernen:

- Mitglied hat weder Schulden noch Forderungen → Deaktivierung möglich.
- Mitglied hat offene finanzielle Positionen → Diese müssen erhalten und weiterhin abrechenbar bleiben.
- Mitglied ist an historischen Ausgaben beteiligt → Historische Kostenanteile bleiben unverändert.
- Nach der Entfernung darf die Person keine neuen Ausgaben erfassen.
- Die Mitgliedschaft kann als `INACTIVE` markiert werden, statt die finanzielle Historie zu löschen.

---

## Phase 13 – Changing Group Roles

*Zusätzlicher Benutzerablauf, unabhängig vom ursprünglichen zehnstufigen Hauptprozess.*

Ein Admin kann:

- Ein normales Gruppenmitglied zum Admin ernennen.
- Einem anderen Admin seine Berechtigung entziehen.
- Die Admin-Rolle übertragen.

Weitere Prüfungen:

- Ein normales Mitglied versucht ohne Berechtigung, andere Mitglieder zu entfernen → Zugriff wird verweigert.
- Der letzte Admin versucht seine Rolle abzugeben → Vorgang erst nach Bestimmung eines Nachfolgers möglich.

---

## Phase 14 – Resolving Disputes

*Zusätzlicher Benutzerablauf, unabhängig vom ursprünglichen zehnstufigen Hauptprozess.*

Mitglieder können uneinig über Ausgaben oder Zahlungen sein:

- Ein Mitglied beanstandet eine Kostenaufteilung.
- Der Erfasser oder ein dazu berechtigtes Mitglied prüft die Beanstandung.
- Eine fehlerhafte Ausgabe wird korrigiert.
- Ein Zahlungsempfänger bestreitet den Erhalt einer Rückzahlung.
- Die Rückzahlung bleibt in diesem Fall unbestätigt.
- Gegebenenfalls unterstützt ein Group Admin bei der Klärung.
- Solange abrechnungsrelevante Streitfälle offen sind, sollte die finale Archivierung verhindert werden.

---

## Phase 15 – Notifications & Activity History

*Zusätzlicher, phasenübergreifender Benutzerablauf.*

### 15.1 Benachrichtigungen

ShareWise informiert betroffene Personen bei relevanten Ereignissen, z. B.:

- Neue Gruppeneinladung.
- Mitglied ist einer Gruppe beigetreten.
- Ausgabe wurde hinzugefügt oder verändert.
- Mitglied wurde aus einer Ausgabe entfernt.
- Rückzahlung wurde erfasst oder bestätigt.
- Gruppe wurde geschlossen oder wieder geöffnet.
- Gruppe wurde archiviert.

### 15.2 Aktivitätsverlauf

- Mitglieder können vergangene Aktivitäten im zulässigen Umfang einsehen.
- Änderungen an Ausgaben und Rückzahlungen werden nachvollziehbar dokumentiert.
- Historische Änderungen unterstützen das Klären von Unstimmigkeiten.

---

## Zusammenfassung

Die **Phasen 1–10** beschreiben den Lebenszyklus der Mallorca-Gruppe von der Registrierung bis zur Archivierung. Die **Phasen 11–15** beschreiben zusätzliche, teilweise parallel auftretende Abläufe für Mitgliedschaft, Berechtigungen, Streitfälle sowie Benachrichtigungen und Historie.

Für die Weiterarbeit im Domain-Driven Design sind besonders die Bereiche **Expense Management**, **Balance Calculation**, **Settlement Management**, **Group Management** und **Membership** relevant.
