import Foundation
import UIKit
import WidgetKit
import SwiftUI
import ActivityKit

struct TripEntry: TimelineEntry {
    let date: Date
    let line: String
    let nextStop: String
    let time: String
    let source: String
    let destination: String
    let platform: String
    let progress: Double
    let statusId: Int
    let generation: Int64
    let active: Bool
    let stale: Bool
}

struct TripProvider: TimelineProvider {
    func placeholder(in context: Context) -> TripEntry {
        TripEntry(date: Date(), line: "Routely", nextStop: "Deine Fahrtbegleitung", time: "", source: "", destination: "", platform: "", progress: 0, statusId: -1, generation: -1, active: false, stale: false)
    }
    func getSnapshot(in context: Context, completion: @escaping (TripEntry) -> Void) { completion(read()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TripEntry>) -> Void) {
        completion(Timeline(entries: [read()], policy: .after(Date().addingTimeInterval(60))))
    }
    private func read() -> TripEntry {
        let shared = UserDefaults(suiteName: "group.de.traewelling.app")
        let data = shared?.data(forKey: "routely.widget.snapshot")
        let value = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any] ?? [:]
        let updated = (value["updatedAtMillis"] as? NSNumber)?.doubleValue ?? 0
        let stale = updated > 0 && Date().timeIntervalSince1970 * 1000 - updated > 120_000
        let active = value["running"] as? Bool != false && value["nextStop"] != nil
        return TripEntry(date: Date(), line: value["line"] as? String ?? "Routely",
            nextStop: active ? value["nextStop"] as? String ?? "Fahrtbegleitung" : "Warte auf deinen Check-in",
            time: stale ? "" : value["timeLabel"] as? String ?? "", source: stale ? "Zuletzt aktualisiert" : value["timeSource"] as? String ?? "",
            destination: value["destination"] as? String ?? "", platform: value["platform"] as? String ?? "",
            progress: min(1, max(0, (value["progress"] as? NSNumber)?.doubleValue ?? 0)),
            statusId: (value["statusId"] as? NSNumber)?.intValue ?? -1,
            generation: (value["generation"] as? NSNumber)?.int64Value ?? -1, active: active, stale: stale)
    }
}

struct TripWidgetView: View {
    let entry: TripEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image("RoutelyBrand").resizable().scaledToFit().frame(width: 26, height: 26)
                Text(entry.line).font(.headline)
                Spacer()
                Image(systemName: "tram.fill").foregroundStyle(.secondary)
            }
            Text(entry.nextStop).font(.title3.bold()).lineLimit(2)
            if !entry.destination.isEmpty { Text("Nach: \(entry.destination)").font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            if entry.active { ProgressView(value: entry.progress).tint(Color(red: 0.38, green: 0.29, blue: 0.80)) }
            HStack(spacing: 5) {
                Text(entry.time).font(.subheadline.bold())
                Text(entry.source).font(.caption).foregroundStyle(.secondary)
                if !entry.platform.isEmpty { Text("Gleis \(entry.platform)").font(.caption) }
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .widgetURL(URL(string: "routely://status/\(entry.statusId)?generation=\(entry.generation)"))
    }
}

struct RoutelyTripWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RoutelyTripWidget", provider: TripProvider()) { entry in
            if #available(iOS 17.0, *) {
                TripWidgetView(entry: entry).containerBackground(.background, for: .widget)
            } else { TripWidgetView(entry: entry) }
        }
        .configurationDisplayName("Routely Fahrt")
        .description("Nächster Halt, Zeitquelle und Fortschritt deiner aktiven Fahrt.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct RoutelyLiveActivity: Widget {
    private func stale(_ context: ActivityViewContext<TripActivityAttributes>) -> Bool {
        if #available(iOS 16.2, *) { return context.isStale }
        return Date().timeIntervalSince(context.state.updatedAt) > 120
    }
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TripActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 7) {
                HStack { Text(context.state.line).font(.headline); Spacer(); Text("Routely").font(.caption) }
                Text(context.state.details ? context.state.nextStop : "Fahrtbegleitung ist aktiv").font(.title3.bold()).lineLimit(1)
                if context.state.details {
                    HStack {
                        if !stale(context) { Text(context.state.timeLabel).bold() }
                        Text(stale(context) ? "Zuletzt aktualisiert" : context.state.timeSource).font(.caption)
                        if !context.state.platform.isEmpty { Text("Gleis \(context.state.platform)").font(.caption) }
                    }
                    ProgressView(value: context.state.progress).tint(stale(context) ? .gray : .purple)
                }
                HStack(spacing: 3) { Spacer(); Text("Stand"); Text(context.state.updatedAt, style: .time) }.font(.caption2).foregroundStyle(.secondary)
            }
            .padding()
            .activityBackgroundTint(Color(.secondarySystemBackground))
            .activitySystemActionForegroundColor(.purple)
            .widgetURL(URL(string: "routely://status/\(context.attributes.statusId)?generation=\(context.attributes.generation)"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label(context.state.line, systemImage: "tram.fill").font(.headline) }
                DynamicIslandExpandedRegion(.trailing) { if !stale(context) { Text(context.state.timeLabel).bold() } }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading) {
                        Text(context.state.nextStop).font(.headline)
                        Text(stale(context) ? "Zuletzt aktualisiert" : context.state.timeSource).font(.caption)
                        ProgressView(value: context.state.progress).tint(stale(context) ? .gray : .purple)
                    }
                }
            } compactLeading: { Image(systemName: "tram.fill") }
              compactTrailing: { Text(stale(context) ? "Stand" : context.state.timeLabel).font(.caption) }
              minimal: { Image(systemName: "tram.fill") }
            .widgetURL(URL(string: "routely://status/\(context.attributes.statusId)?generation=\(context.attributes.generation)"))
            .keylineTint(.purple)
        }
    }
}

@main
struct RoutelyWidgets: WidgetBundle {
    var body: some Widget { RoutelyTripWidget(); RoutelyLiveActivity() }
}
