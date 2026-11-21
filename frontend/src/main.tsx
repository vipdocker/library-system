import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App';
import './styles/global.css';
import { applyLinearCssVariables } from './styles/linearTheme';

dayjs.locale('zh-cn');
applyLinearCssVariables(document.documentElement);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);