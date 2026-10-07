import ActivityKit
import Foundation

struct TripActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var line: String
        var nextStop: String
        var destination: String
        var timeLabel: String
        var timeSource: String
        var platform: String
        var progress: Double
        var details: Bool
        var updatedAt: Date
    }
    var statusId: Int
    var generation: Int64
    var sessionRevision: String
}
