import SwiftUI

struct ContentView: View {
    var body: some View {
        EP133WebView()
            .ignoresSafeArea()
            .statusBarHidden(false)
            .preferredColorScheme(.dark)
    }
}
