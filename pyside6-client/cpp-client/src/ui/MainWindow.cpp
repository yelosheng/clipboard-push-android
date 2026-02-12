#include "MainWindow.h"

#include <QWidget>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QFont>

namespace ClipboardPush {

MainWindow::MainWindow(QWidget* parent)
    : QMainWindow(parent)
{
    setWindowTitle("Clipboard Push");
    setFixedSize(400, 300);
    setupUi();
}

void MainWindow::setupUi() {
    QWidget* centralWidget = new QWidget(this);
    setCentralWidget(centralWidget);

    QVBoxLayout* layout = new QVBoxLayout(centralWidget);
    layout->setContentsMargins(15, 15, 15, 15);
    layout->setSpacing(10);

    // Text Area
    m_textEdit = new QTextEdit(this);
    m_textEdit->setPlaceholderText("Enter text to push... (Ctrl+Enter to send)");
    m_textEdit->installEventFilter(this);
    layout->addWidget(m_textEdit);

    // Buttons layout
    QHBoxLayout* btnLayout = new QHBoxLayout();

    m_pushButton = new QPushButton("Push", this);
    m_pushButton->setFixedHeight(40);
    connect(m_pushButton, &QPushButton::clicked, this, &MainWindow::onPushClicked);

    m_settingsButton = new QPushButton(QString::fromUtf8("\xE2\x9A\x99"), this);  // Gear icon
    m_settingsButton->setFixedSize(40, 40);
    m_settingsButton->setToolTip("Settings");
    m_settingsButton->setFont(QFont("Segoe UI", 12));
    connect(m_settingsButton, &QPushButton::clicked, this, &MainWindow::settingsClicked);

    btnLayout->addWidget(m_pushButton, 1);
    btnLayout->addWidget(m_settingsButton);
    layout->addLayout(btnLayout);

    // Status Label
    m_statusLabel = new QLabel("Ready", this);
    m_statusLabel->setAlignment(Qt::AlignCenter);
    m_statusLabel->setStyleSheet("color: #666; font-size: 11px;");
    layout->addWidget(m_statusLabel);

    setFont(QFont("Segoe UI", 10));
}

bool MainWindow::eventFilter(QObject* obj, QEvent* event) {
    if (obj == m_textEdit && event->type() == QEvent::KeyPress) {
        QKeyEvent* keyEvent = static_cast<QKeyEvent*>(event);
        if (keyEvent->key() == Qt::Key_Return &&
            (keyEvent->modifiers() & Qt::ControlModifier)) {
            onPushClicked();
            return true;
        }
    }
    return QMainWindow::eventFilter(obj, event);
}

void MainWindow::onPushClicked() {
    QString text = m_textEdit->toPlainText();
    if (text.trimmed().isEmpty()) {
        setStatus("Please enter text", "red");
        return;
    }

    emit pushTextClicked(text);
    m_textEdit->clear();
}

void MainWindow::setStatus(const QString& text, const QString& color) {
    m_statusLabel->setText(text);
    m_statusLabel->setStyleSheet(QString("color: %1; font-size: 11px;").arg(color));
}

} // namespace ClipboardPush
