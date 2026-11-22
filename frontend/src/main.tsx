import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App';
import './styles/global.css';
import { applyClaudeCssVariables, claudeFaviconUri } from './styles/claudeTheme';

dayjs.locale('zh-cn');
applyClaudeCssVariables(document.documentElement);
// favicon 与主题同源：index.html 只保留 link 元素，色值由主题模块生成。
document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.setAttribute('href', claudeFaviconUri);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);