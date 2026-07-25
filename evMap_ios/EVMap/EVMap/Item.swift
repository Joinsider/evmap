//
//  Item.swift
//  EVMap
//
//  Created by Johannes Popp on 25.07.26.
//

import Foundation
import SwiftData

@Model
final class Item {
    var timestamp: Date
    
    init(timestamp: Date) {
        self.timestamp = timestamp
    }
}
