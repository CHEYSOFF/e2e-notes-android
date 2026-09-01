import SwiftUI
import MananaApp

// The Swift half of the app, and deliberately all of it.
//
// Everything below is plumbing: hand the window a UIViewController that Kotlin builds, and get out
// of the way. There is no Swift model, no Swift view and no Swift state, because every one of those
// would be a second implementation of something the shared code already has -- and the whole point
// of this port is that an iPhone and a Pixel agree, which they cannot do if half the iPhone is
// written separately.
//
// NOT COMPILED. Swift is compiled by Xcode on macOS; this file has never been through a compiler.
// See docs/BUILDING-IOS.md, which also carries the Xcode project setup this file assumes.

/// Bridges `MainViewControllerKt.mainViewController()` into SwiftUI.
///
/// The Kotlin function is a top-level `fun mainViewController()` in `MainViewController.kt`, and
/// Kotlin/Native exports such a function as a static method on a class named after the file. So the
/// file name and the function name are both part of this contract: renaming either breaks this line
/// with a Swift error that does not mention Kotlin.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Nothing. Compose owns everything inside the controller and re-renders itself; SwiftUI has
        // no state here to push down.
    }
}

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose draws its own background and handles its own insets through
                // `WindowInsets.safeDrawing`, so SwiftUI must not also inset the view -- doing both
                // double-pads every screen and is a common way for a Compose-on-iOS app to look
                // subtly wrong at the top.
                .ignoresSafeArea(.all)
                // The palette is a locked dark one (`core-ui`'s Color.kt, and `MananaTheme` on the
                // Kotlin side). Without this, the system draws light-mode chrome -- the status bar
                // text in particular -- over a black app.
                .preferredColorScheme(.dark)
        }
    }
}
